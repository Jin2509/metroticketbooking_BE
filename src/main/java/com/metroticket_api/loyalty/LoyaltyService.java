package com.metroticket_api.loyalty;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.audit.AuditLogService;
import com.metroticket_api.loyalty.LoyaltyTransaction.Type;
import com.metroticket_api.notification.NotificationDispatcher;
import com.metroticket_api.notification.NotificationType;
import com.metroticket_api.system.SystemParamKey;
import com.metroticket_api.system.SystemParamService;

/**
 * Điểm CPoint của "Hành trình xanh" (UC12, UC15): mỗi lượt check-in hợp lệ được cộng số điểm cố định theo tham số hệ
 * thống, 1 điểm tương ứng 1 đồng khi đặt vé. Mọi thay đổi số dư đều khoá dòng tài khoản để không lệch khi đồng thời.
 */
@Service
public class LoyaltyService {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    public record TierView(String code, String name, int minPoints) {
        static TierView of(MembershipTier t) {
            return t == null ? null : new TierView(t.getCode(), t.getName(), t.getMinPoints());
        }
    }

    public record Summary(int pointsBalance, int lifetimePoints, TierView tier, TierView nextTier, int maxDiscountVnd,
            int pointsPerCheckIn) {
    }

    private final LoyaltyAccountRepository accounts;
    private final LoyaltyTransactionRepository transactions;
    private final MembershipTierRepository tiers;
    private final SystemParamService params;
    private final NotificationDispatcher notifier;
    private final AuditLogService audit;
    private final Clock clock;

    public LoyaltyService(LoyaltyAccountRepository accounts, LoyaltyTransactionRepository transactions,
            MembershipTierRepository tiers, SystemParamService params, NotificationDispatcher notifier,
            AuditLogService audit, Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.tiers = tiers;
        this.params = params;
        this.notifier = notifier;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Summary summary(UUID userId) {
        LoyaltyAccount account = accounts.findByUserId(userId).orElse(null);
        int balance = account == null ? 0 : account.getPointsBalance();
        int lifetime = account == null ? 0 : account.getLifetimePoints();
        List<MembershipTier> active = activeTiers();
        return new Summary(balance, lifetime, TierView.of(tierFor(active, lifetime)), TierView.of(nextTier(active, lifetime)),
                params.intValue(SystemParamKey.CPOINT_MAX_DISCOUNT_VND), params.intValue(SystemParamKey.CPOINT_PER_CHECKIN));
    }

    @Transactional(readOnly = true)
    public Page<LoyaltyTransaction> history(UUID userId, Pageable pageable) {
        return transactions.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable);
    }

    /** Cộng điểm cho một lượt check-in đã được chấp nhận. Gọi lại với cùng lượt thì bỏ qua. */
    @Transactional
    public void earnForCheckIn(UUID userId, Long validationId) {
        if (transactions.existsByValidationIdAndType(validationId, Type.EARN)) {
            return;
        }
        int points = params.intValue(SystemParamKey.CPOINT_PER_CHECKIN);
        if (points <= 0) {
            return;
        }
        LoyaltyAccount account = lockAccount(userId);
        Instant now = clock.instant();
        Instant dayStart = LocalDate.ofInstant(now, VIETNAM).atStartOfDay(VIETNAM).toInstant();
        if (transactions.countByUserIdAndTypeAndCreatedAtGreaterThanEqual(userId, Type.EARN, dayStart)
                >= params.intValue(SystemParamKey.CPOINT_MAX_CHECKINS_PER_DAY)) {
            return;
        }
        account.earn(points, now);
        transactions.save(new LoyaltyTransaction(userId, validationId, null, null, Type.EARN, points,
                account.getPointsBalance(), "Tích điểm khi check-in", now));
        refreshTier(account, now);
    }

    /**
     * Trừ điểm vào đơn trong transaction đặt vé.
     *
     * @return số tiền được giảm (bằng số điểm); 0 nếu không dùng điểm
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int redeem(UUID userId, int points, int amountVnd, String referenceType, String referenceId) {
        if (points <= 0) {
            return 0;
        }
        LoyaltyAccount account = lockAccount(userId);
        int maxUsable = Math.min(account.getPointsBalance(),
                Math.min(params.intValue(SystemParamKey.CPOINT_MAX_DISCOUNT_VND), amountVnd));
        if (points > maxUsable) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Số điểm không hợp lệ, đơn này dùng được tối đa " + maxUsable + " điểm");
        }
        Instant now = clock.instant();
        account.spend(points, now);
        transactions.save(new LoyaltyTransaction(userId, null, referenceType, referenceId, Type.REDEEM, -points,
                account.getPointsBalance(), "Dùng điểm giảm giá đơn", now));
        return points;
    }

    /** Hoàn điểm khi đơn hết hạn hoặc bị huỷ. Gọi lại với cùng đơn thì bỏ qua. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restore(UUID userId, int points, String referenceType, String referenceId) {
        if (points <= 0 || transactions.existsByReferenceTypeAndReferenceIdAndType(referenceType, referenceId, Type.RESTORE)) {
            return;
        }
        LoyaltyAccount account = lockAccount(userId);
        Instant now = clock.instant();
        account.giveBack(points, now);
        transactions.save(new LoyaltyTransaction(userId, null, referenceType, referenceId, Type.RESTORE, points,
                account.getPointsBalance(), "Hoàn điểm do đơn không được thanh toán", now));
    }

    @Transactional(readOnly = true)
    public List<TierView> listTiers() {
        return activeTiers().stream().map(TierView::of).toList();
    }

    /** UC09 A2: sửa tên và ngưỡng hạng. Thứ tự ngưỡng phải tăng dần theo thứ hạng hiện có. */
    @Transactional
    public TierView updateTier(String code, String name, int minPoints) {
        List<MembershipTier> active = activeTiers();
        MembershipTier tier = active.stream().filter(t -> t.getCode().equalsIgnoreCase(code)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hạng " + code));
        int index = active.indexOf(tier);
        boolean tooLow = index > 0 && minPoints <= active.get(index - 1).getMinPoints();
        boolean tooHigh = index < active.size() - 1 && minPoints >= active.get(index + 1).getMinPoints();
        if (tooLow || tooHigh || (index == 0 && minPoints != 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ngưỡng hạng phải tăng dần và hạng thấp nhất có ngưỡng 0");
        }
        String old = tier.getName() + ":" + tier.getMinPoints();
        tier.update(name.trim(), minPoints);
        audit.log("MEMBERSHIP_TIER_UPDATE", "membership_tier", tier.getId(), tier.getCode(), old, name.trim() + ":" + minPoints);
        return TierView.of(tier);
    }

    private LoyaltyAccount lockAccount(UUID userId) {
        accounts.insertIfAbsent(userId);
        return accounts.findByUserIdForUpdate(userId).orElseThrow();
    }

    private void refreshTier(LoyaltyAccount account, Instant now) {
        MembershipTier target = tierFor(activeTiers(), account.getLifetimePoints());
        if (target == null || (account.getTier() != null && account.getTier().getId().equals(target.getId()))) {
            return;
        }
        boolean firstTier = account.getTier() == null;
        account.changeTier(target, now);
        if (!firstTier) {
            notifier.dispatch(NotificationDispatcher.Request
                    .of(account.getUserId(), NotificationType.GREEN_JOURNEY, "Chúc mừng! Bạn đạt hạng " + target.getName(),
                            "Tổng điểm tích lũy của bạn đã đạt " + account.getLifetimePoints() + " điểm.")
                    .dedupe("LOYALTY_TIER:" + target.getCode()));
        }
    }

    private List<MembershipTier> activeTiers() {
        return tiers.findByStatusOrderByMinPointsAsc("ACTIVE");
    }

    static MembershipTier tierFor(List<MembershipTier> sorted, int lifetime) {
        return sorted.stream().filter(t -> t.getMinPoints() <= lifetime)
                .max(Comparator.comparingInt(MembershipTier::getMinPoints)).orElse(null);
    }

    private static MembershipTier nextTier(List<MembershipTier> sorted, int lifetime) {
        return sorted.stream().filter(t -> t.getMinPoints() > lifetime).findFirst().orElse(null);
    }
}
