package com.metroticket_api.notification.campaign;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.station.StationRepository;
import com.metroticket_api.notification.NotificationDeliveryRepository;
import com.metroticket_api.notification.NotificationPriority;
import com.metroticket_api.notification.NotificationRepository;
import com.metroticket_api.notification.NotificationType;
import com.metroticket_api.notification.NotificationsCreatedEvent;
import com.metroticket_api.notification.campaign.CampaignDtos.CampaignRequest;
import com.metroticket_api.notification.campaign.CampaignDtos.CampaignResponse;
import com.metroticket_api.notification.campaign.CampaignDtos.PreviewResponse;
import com.metroticket_api.notification.campaign.CampaignDtos.StatsResponse;
import com.metroticket_api.notification.push.PushSender;
import com.metroticket_api.rbac.RoleRepository;
import com.metroticket_api.security.CurrentUser;
import com.metroticket_api.user.UserRepository;

/**
 * Chiến dịch thông báo của admin (NOTIFICATION_MANAGE).
 *
 * <ul>
 *   <li><b>Tạo/sửa</b>: chỉ DRAFT/SCHEDULED; bắt buộc gửi đúng version. Loại: ANNOUNCEMENT, PROMOTION, SYSTEM (không cho
 *       giả SECURITY / thông báo thanh toán để tránh lừa đảo). Link chỉ là màn hình trong app.</li>
 *   <li><b>Đối tượng</b>: vai trò/tuyến/ga phải tồn tại; USERS 1–5000 người tồn tại. Xem trước số người nhận.</li>
 *   <li><b>Gửi</b>: ngay hoặc hẹn giờ (tối đa 90 ngày tới). Không gửi khi không có người nhận. Email chỉ khi số người nhận
 *       ≤ app.notification.max-email-recipients (chống spam, giới hạn SMTP).</li>
 *   <li><b>Huỷ</b> trước khi gửi; <b>xoá</b> chỉ bản DRAFT. Đã gửi thì giữ nguyên để đối soát.</li>
 * </ul>
 */
@Service
public class CampaignService {

    static final Duration MAX_SCHEDULE_AHEAD = Duration.ofDays(90);

    private final NotificationCampaignRepository campaigns;
    private final CampaignAudience audience;
    private final NotificationRepository notifications;
    private final NotificationDeliveryRepository deliveries;
    private final RoleRepository roles;
    private final MetroLineRepository lines;
    private final StationRepository stations;
    private final UserRepository users;
    private final PushSender push;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final int maxEmailRecipients;

    public CampaignService(NotificationCampaignRepository campaigns, CampaignAudience audience,
            NotificationRepository notifications, NotificationDeliveryRepository deliveries, RoleRepository roles,
            MetroLineRepository lines, StationRepository stations, UserRepository users, PushSender push,
            ApplicationEventPublisher events, Clock clock,
            @Value("${app.notification.max-email-recipients:2000}") int maxEmailRecipients) {
        this.campaigns = campaigns;
        this.audience = audience;
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.roles = roles;
        this.lines = lines;
        this.stations = stations;
        this.users = users;
        this.push = push;
        this.events = events;
        this.clock = clock;
        this.maxEmailRecipients = maxEmailRecipients;
    }

    @Transactional(readOnly = true)
    public Page<CampaignResponse> list(CampaignStatus status, Pageable pageable) {
        return (status == null ? campaigns.findAllByOrderByCreatedAtDesc(pageable)
                : campaigns.findByStatusOrderByCreatedAtDesc(status, pageable)).map(CampaignResponse::of);
    }

    @Transactional(readOnly = true)
    public CampaignResponse get(UUID id) {
        return CampaignResponse.of(require(id));
    }

    @Transactional
    public CampaignResponse create(CampaignRequest r) {
        validate(r);
        NotificationCampaign c = new NotificationCampaign(r.title().trim(), r.body().trim(), r.type(), r.priority(),
                blankToNull(r.link()), r.audienceType(), normalizedValue(r), !Boolean.FALSE.equals(r.push()),
                Boolean.TRUE.equals(r.email()), CurrentUser.optionalId().orElse(null), clock.instant());
        campaigns.saveAndFlush(c);
        if (r.audienceType() == AudienceType.USERS) {
            audience.addUsers(c, r.userIds());
        }
        return CampaignResponse.of(c);
    }

    @Transactional
    public CampaignResponse update(UUID id, long version, CampaignRequest r) {
        NotificationCampaign c = requireVersion(id, version);
        if (!c.getStatus().editable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chiến dịch đã gửi/đã huỷ, không sửa được");
        }
        validate(r);
        c.edit(r.title().trim(), r.body().trim(), r.type(), r.priority(), blankToNull(r.link()), r.audienceType(),
                normalizedValue(r), !Boolean.FALSE.equals(r.push()), Boolean.TRUE.equals(r.email()),
                CurrentUser.optionalId().orElse(null), clock.instant());
        if (c.getStatus() == CampaignStatus.SCHEDULED) {
            // sửa nội dung bản đã hẹn giờ → về nháp, phải bấm gửi lại (tránh gửi nội dung chưa kiểm tra)
            c.unschedule(CurrentUser.optionalId().orElse(null), clock.instant());
        }
        campaigns.saveAndFlush(c);
        if (r.audienceType() == AudienceType.USERS) {
            audience.addUsers(c, r.userIds());
        }
        return CampaignResponse.of(c);
    }

    @Transactional(readOnly = true)
    public PreviewResponse preview(UUID id) {
        int n = audience.count(require(id), clock.instant());
        return new PreviewResponse(n, n <= maxEmailRecipients, maxEmailRecipients);
    }

    @Transactional
    public CampaignResponse send(UUID id, long version, Instant scheduledAt) {
        NotificationCampaign c = requireVersion(id, version);
        if (c.getStatus() != CampaignStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ gửi được chiến dịch đang ở bản nháp");
        }
        Instant now = clock.instant();
        Instant at = scheduledAt == null ? now : scheduledAt;
        if (at.isBefore(now.minusSeconds(60))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thời điểm hẹn gửi đã qua");
        }
        if (at.isAfter(now.plus(MAX_SCHEDULE_AHEAD))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chỉ hẹn gửi tối đa 90 ngày tới");
        }
        int recipients = audience.count(c, now);
        if (recipients == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Không có người nhận phù hợp với đối tượng đã chọn");
        }
        if (c.isChannelEmail() && recipients > maxEmailRecipients) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Gửi email tối đa " + maxEmailRecipients
                    + " người mỗi chiến dịch (hiện " + recipients + "); hãy tắt email hoặc thu hẹp đối tượng");
        }
        c.schedule(at, CurrentUser.optionalId().orElse(null), now);
        return CampaignResponse.of(campaigns.saveAndFlush(c));
    }

    @Transactional
    public CampaignResponse cancel(UUID id, long version) {
        NotificationCampaign c = requireVersion(id, version);
        if (!c.getStatus().editable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chiến dịch đã bắt đầu gửi, không huỷ được");
        }
        c.cancel(CurrentUser.optionalId().orElse(null), clock.instant());
        return CampaignResponse.of(campaigns.saveAndFlush(c));
    }

    @Transactional
    public void delete(UUID id) {
        NotificationCampaign c = require(id);
        if (c.getStatus() != CampaignStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ xoá được bản nháp; chiến dịch khác hãy huỷ");
        }
        campaigns.delete(c);
    }

    @Transactional(readOnly = true)
    public StatsResponse stats(UUID id) {
        NotificationCampaign c = require(id);
        Map<String, Map<String, Long>> byChannel = new LinkedHashMap<>();
        for (var row : deliveries.statsForCampaign(id)) {
            byChannel.computeIfAbsent(row.getChannel(), k -> new LinkedHashMap<>()).put(row.getStatus(), row.getTotal());
        }
        return new StatsResponse(id, c.getStatus(), notifications.countByCampaignId(id),
                notifications.countByCampaignIdAndReadAtIsNotNull(id), byChannel);
    }

    /** Worker: tạo thông báo + hàng đợi gửi cho chiến dịch đến hạn. Cả chiến dịch trong một transaction. */
    @Transactional
    public boolean runDue() {
        Instant now = clock.instant();
        var ids = campaigns.lockDue(now);
        if (ids.isEmpty()) {
            return false;
        }
        NotificationCampaign c = campaigns.findById(ids.get(0)).orElseThrow();
        c.start(now);
        int recipients = audience.insertNotifications(c, now);
        int pushQueued = c.isChannelPush() && push.available() ? audience.queuePush(c, now) : 0;
        int emailQueued = c.isChannelEmail() ? audience.queueEmail(c, now) : 0;
        c.complete(recipients, pushQueued, emailQueued, now);
        events.publishEvent(NotificationsCreatedEvent.campaign(c.getId()));
        return true;
    }

    /** Chiến dịch lỗi bị rollback về SCHEDULED: đánh dấu FAILED (transaction riêng) để không lặp lỗi mãi. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void failDue(String error) {
        campaigns.lockDue(clock.instant()).stream().findFirst().flatMap(campaigns::findById)
                .ifPresent(c -> c.fail(error, clock.instant()));
    }

    /** Tạo chiến dịch tự động từ thông báo vận hành vừa được duyệt (gọi trong transaction duyệt). */
    @Transactional
    public void scheduleFromAnnouncement(Long announcementId, String title, String body, boolean critical,
            AudienceType type, String value, Instant startsAt) {
        if (campaigns.existsByAnnouncementId(announcementId)) {
            return;
        }
        Instant now = clock.instant();
        NotificationCampaign c = new NotificationCampaign(truncate(title, 200), truncate(body, 2000),
                NotificationType.ANNOUNCEMENT, critical ? NotificationPriority.HIGH : NotificationPriority.NORMAL,
                "/announcements/" + announcementId, type, value, true, false, CurrentUser.optionalId().orElse(null), now);
        c.fromAnnouncement(announcementId);
        c.schedule(startsAt == null || startsAt.isBefore(now) ? now : startsAt, CurrentUser.optionalId().orElse(null), now);
        campaigns.save(c);
    }

    private void validate(CampaignRequest r) {
        if (r.type() != NotificationType.ANNOUNCEMENT && r.type() != NotificationType.PROMOTION
                && r.type() != NotificationType.SYSTEM) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Chiến dịch chỉ dùng loại ANNOUNCEMENT, PROMOTION hoặc SYSTEM");
        }
        String value = r.audienceValue() == null ? null : r.audienceValue().trim();
        switch (r.audienceType()) {
            case ROLE -> {
                if (value == null || roles.findByCode(value.toUpperCase()).isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vai trò không tồn tại: " + value);
                }
            }
            case LINE -> {
                if (parseId(value).filter(lines::existsById).isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không có tuyến " + value);
                }
            }
            case STATION -> {
                if (parseId(value).filter(stations::existsById).isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không có ga " + value);
                }
            }
            case USERS -> {
                if (r.userIds() == null || r.userIds().isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chọn ít nhất một người nhận");
                }
                if (users.findAllById(r.userIds()).size() != r.userIds().size()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Có người nhận không tồn tại");
                }
            }
            default -> { }
        }
    }

    private static String normalizedValue(CampaignRequest r) {
        return switch (r.audienceType()) {
            case ROLE -> r.audienceValue().trim().toUpperCase();
            case LINE, STATION -> r.audienceValue().trim();
            default -> null;
        };
    }

    private static java.util.Optional<Long> parseId(String value) {
        try {
            return value == null ? java.util.Optional.empty() : java.util.Optional.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    private NotificationCampaign require(UUID id) {
        return campaigns.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chiến dịch"));
    }

    private NotificationCampaign requireVersion(UUID id, long version) {
        NotificationCampaign c = require(id);
        if (c.getVersion() != version) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chiến dịch vừa được người khác thay đổi, hãy tải lại");
        }
        return c;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
