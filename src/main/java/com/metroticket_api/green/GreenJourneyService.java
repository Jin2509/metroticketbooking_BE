package com.metroticket_api.green;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.green.GreenJourneyDtos.LeaderboardEntry;
import com.metroticket_api.green.GreenJourneyDtos.LeaderboardResponse;
import com.metroticket_api.green.GreenJourneyDtos.LevelInfo;
import com.metroticket_api.green.GreenJourneyDtos.PublicStatsResponse;
import com.metroticket_api.green.GreenJourneyDtos.SettingsResponse;
import com.metroticket_api.green.GreenJourneyDtos.SummaryResponse;
import com.metroticket_api.green.GreenJourneyDtos.Totals;
import com.metroticket_api.green.GreenJourneyDtos.TripResponse;
import com.metroticket_api.green.GreenJourneyDtos.UpdateSettingsRequest;
import com.metroticket_api.metro.map.MapService;
import com.metroticket_api.metro.map.RoutePlanner;
import com.metroticket_api.metro.map.RoutePlanner.Optimize;
import com.metroticket_api.metro.station.Station;
import com.metroticket_api.security.CurrentUser;
import com.metroticket_api.user.User;
import com.metroticket_api.user.UserRepository;

/**
 * Hành trình xanh: mỗi chuyến đi Metro hoàn thành được quy đổi thành lượng CO2 tiết kiệm so với đi xe máy và điểm xanh.
 *
 * <ul>
 *   <li>Quãng đường = lộ trình ngắn nhất giữa ga vào và ga ra trên mạng lưới (không tin số liệu từ client).</li>
 *   <li>CO2 tiết kiệm = km × (phát thải xe máy − phát thải Metro) g/km; điểm = km × điểm/km (làm tròn xuống).</li>
 *   <li>Chống "cày điểm": ra cùng ga lúc vào, chuyến quá ngắn (&lt; min phút), quá dài (&gt; max phút), hoặc vượt số chuyến
 *       tính điểm mỗi ngày → vẫn ghi nhận chuyến nhưng 0 điểm, kèm lý do.</li>
 *   <li>Lỗi khi tính (vd. không tìm được lộ trình) không bao giờ làm hỏng lượt quẹt ra ở cổng.</li>
 * </ul>
 */
@Service
public class GreenJourneyService {

    private static final Logger log = LoggerFactory.getLogger(GreenJourneyService.class);
    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private final GreenTripRepository trips;
    private final GreenJourneySettingsRepository settingsRepository;
    private final MapService mapService;
    private final UserRepository users;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.metroticket_api.notification.NotificationDispatcher notifier;

    public GreenJourneyService(GreenTripRepository trips, GreenJourneySettingsRepository settingsRepository,
            MapService mapService, UserRepository users, Clock clock) {
        this.trips = trips;
        this.settingsRepository = settingsRepository;
        this.mapService = mapService;
        this.users = users;
        this.clock = clock;
    }

    public record Calculation(BigDecimal distanceKm, int co2Grams, int points, boolean counted, String reason) {
    }

    /** Gọi từ GateService khi chấp nhận quẹt ra, trong cùng transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordTrip(UUID userId, UUID ticketId, Station entry, Instant entryAt, Station exit, Instant exitAt) {
        try {
            GreenJourneySettings settings = settingsRepository.current();
            if (!settings.isEnabled() || entry == null || entryAt == null || trips.existsByTicketIdAndEntryAt(ticketId, entryAt)) {
                return;
            }
            BigDecimal distance = BigDecimal.ZERO;
            if (!entry.getId().equals(exit.getId())) {
                distance = new RoutePlanner(mapService.loadNetwork())
                        .plan(entry.getId(), exit.getId(), Optimize.SHORTEST)
                        .map(RoutePlanner.Journey::totalDistanceKm)
                        .orElse(BigDecimal.ZERO);
            }
            Instant dayStart = LocalDate.ofInstant(exitAt, VIETNAM).atStartOfDay(VIETNAM).toInstant();
            long countedToday = trips.countByUserIdAndCountedTrueAndExitAtGreaterThanEqual(userId, dayStart);
            Calculation c = calculate(settings, entry.getId().equals(exit.getId()), distance,
                    Duration.between(entryAt, exitAt), countedToday);
            long before = c.counted() ? trips.totalsFor(userId, Instant.EPOCH).getCo2Grams() : 0;
            trips.save(new GreenTrip(userId, ticketId, entry, exit, entryAt, exitAt, c.distanceKm(), c.co2Grams(),
                    c.points(), c.counted(), c.reason(), clock.instant()));
            GreenLevel was = GreenLevel.of(before);
            GreenLevel now = GreenLevel.of(before + c.co2Grams());
            if (c.counted() && now != was && notifier != null) {
                notifier.dispatch(com.metroticket_api.notification.NotificationDispatcher.Request
                        .of(userId, com.metroticket_api.notification.NotificationType.GREEN_JOURNEY,
                                "Chúc mừng! Bạn đạt cấp " + now.label(),
                                "Bạn đã giúp giảm " + kg(before + c.co2Grams()) + " kg CO2 nhờ đi Metro.")
                        .link("/green-journey").dedupe("GREEN_LEVEL:" + now.name()));
            }
        } catch (RuntimeException e) {
            log.warn("Không ghi được Hành trình xanh cho vé {}: {}", ticketId, e.getMessage());
        }
    }

    static Calculation calculate(GreenJourneySettings s, boolean sameStation, BigDecimal distanceKm, Duration duration,
            long countedToday) {
        BigDecimal km = distanceKm.setScale(3, RoundingMode.HALF_UP);
        String reason = null;
        if (sameStation) {
            reason = "Ra cùng ga lúc vào";
        } else if (km.signum() == 0) {
            reason = "Không xác định được lộ trình giữa 2 ga";
        } else if (duration.toMinutes() < s.getMinTripMinutes()) {
            reason = "Chuyến đi ngắn hơn " + s.getMinTripMinutes() + " phút";
        } else if (duration.toMinutes() > s.getMaxTripMinutes()) {
            reason = "Chuyến đi dài hơn " + s.getMaxTripMinutes() + " phút";
        } else if (countedToday >= s.getMaxCountedTripsPerDay()) {
            reason = "Đã đạt tối đa " + s.getMaxCountedTripsPerDay() + " chuyến tính điểm trong ngày";
        }
        if (reason != null) {
            return new Calculation(km, 0, 0, false, reason);
        }
        int saved = km.multiply(BigDecimal.valueOf(s.getBaselineGramsPerKm() - s.getMetroGramsPerKm()))
                .setScale(0, RoundingMode.HALF_UP).intValueExact();
        int points = km.multiply(BigDecimal.valueOf(s.getPointsPerKm())).setScale(0, RoundingMode.DOWN).intValueExact();
        return new Calculation(km, saved, points, true, null);
    }

    @Transactional(readOnly = true)
    public SummaryResponse summary() {
        UUID userId = CurrentUser.id();
        GreenJourneySettings s = settingsRepository.current();
        Instant monthStart = monthStart(YearMonth.now(clock.withZone(VIETNAM)));
        Totals all = totals(trips.totalsFor(userId, Instant.EPOCH), s);
        Totals month = totals(trips.totalsFor(userId, monthStart), s);
        long grams = all.co2SavedKg().movePointRight(3).longValue();
        GreenLevel level = GreenLevel.of(grams);
        GreenLevel next = level.next();
        LevelInfo levelInfo = new LevelInfo(level, level.label(), next, next == null ? null : next.label(),
                next == null ? BigDecimal.ZERO : kg(next.minGrams() - grams));
        Integer rank = month.points() > 0
                ? (int) trips.usersAhead(monthStart, monthEnd(YearMonth.now(clock.withZone(VIETNAM))), month.points()) + 1
                : null;
        String message = all.trips() == 0 ? "Hãy đi Metro để bắt đầu Hành trình xanh của bạn!"
                : "Bạn đã giúp giảm " + all.co2SavedKg() + " kg CO2, tương đương " + all.treeYearsEquivalent()
                        + " cây xanh hấp thụ trong một năm.";
        return new SummaryResponse(s.isEnabled(), all, month, levelInfo, rank, message);
    }

    @Transactional(readOnly = true)
    public Page<TripResponse> myTrips(Pageable pageable) {
        return trips.findByUser(CurrentUser.id(), pageable).map(TripResponse::of);
    }

    @Transactional(readOnly = true)
    public LeaderboardResponse leaderboard(String month, int limit) {
        YearMonth ym = parseMonth(month);
        Optional<UUID> me = CurrentUser.optionalId();
        List<GreenTripRepository.Ranking> rows = trips.leaderboard(monthStart(ym), monthEnd(ym),
                PageRequest.of(0, Math.clamp(limit, 1, 50)));
        Map<UUID, User> byId = users.findAllById(rows.stream().map(GreenTripRepository.Ranking::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        List<LeaderboardEntry> entries = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            GreenTripRepository.Ranking r = rows.get(i);
            User u = byId.get(r.getUserId());
            entries.add(new LeaderboardEntry(i + 1, maskName(u == null ? null : u.getFullName()), r.getPoints(),
                    kg(r.getCo2Grams()), r.getTrips(), me.map(r.getUserId()::equals).orElse(false)));
        }
        return new LeaderboardResponse(ym.toString(), entries);
    }

    @Transactional(readOnly = true)
    public PublicStatsResponse publicStats() {
        Totals t = totals(trips.totalsAll(Instant.EPOCH), settingsRepository.current());
        return new PublicStatsResponse(t.trips(), t.distanceKm(), t.co2SavedKg(), t.treeYearsEquivalent());
    }

    @Transactional(readOnly = true)
    public SettingsResponse settings() {
        return SettingsResponse.of(settingsRepository.current());
    }

    @Transactional
    public SettingsResponse updateSettings(UpdateSettingsRequest r) {
        GreenJourneySettings s = settingsRepository.findById((short) 1)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Thiếu dòng cấu hình (V18)"));
        if (r.version() != s.getVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cấu hình vừa được người khác thay đổi, hãy tải lại");
        }
        if (r.enabled() != null) s.setEnabled(r.enabled());
        if (r.baselineGramsPerKm() != null) s.setBaselineGramsPerKm(r.baselineGramsPerKm());
        if (r.metroGramsPerKm() != null) s.setMetroGramsPerKm(r.metroGramsPerKm());
        if (r.pointsPerKm() != null) s.setPointsPerKm(r.pointsPerKm());
        if (r.minTripMinutes() != null) s.setMinTripMinutes(r.minTripMinutes());
        if (r.maxTripMinutes() != null) s.setMaxTripMinutes(r.maxTripMinutes());
        if (r.maxCountedTripsPerDay() != null) s.setMaxCountedTripsPerDay(r.maxCountedTripsPerDay());
        if (r.treeGramsPerYear() != null) s.setTreeGramsPerYear(r.treeGramsPerYear());
        if (s.getMetroGramsPerKm() >= s.getBaselineGramsPerKm()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phát thải Metro phải nhỏ hơn phương tiện so sánh");
        }
        if (s.getMinTripMinutes() >= s.getMaxTripMinutes()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minTripMinutes phải nhỏ hơn maxTripMinutes");
        }
        s.touch(CurrentUser.optionalId().orElse(null), clock.instant());
        settingsRepository.saveAndFlush(s);
        return SettingsResponse.of(s);
    }

    private static Totals totals(GreenTripRepository.Totals t, GreenJourneySettings s) {
        BigDecimal km = t.getDistanceKm() == null ? BigDecimal.ZERO : t.getDistanceKm().setScale(3, RoundingMode.HALF_UP);
        BigDecimal trees = BigDecimal.valueOf(t.getCo2Grams())
                .divide(BigDecimal.valueOf(s.getTreeGramsPerYear()), 2, RoundingMode.HALF_UP);
        return new Totals(t.getTrips(), km, kg(t.getCo2Grams()), t.getPoints(), trees);
    }

    private static BigDecimal kg(long grams) {
        return BigDecimal.valueOf(grams).movePointLeft(3).setScale(2, RoundingMode.HALF_UP);
    }

    /** "Nguyễn Văn An" → "Nguyễn V. A." */
    static String maskName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "Hành khách";
        }
        String[] parts = fullName.trim().split("\\s+");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(' ').append(parts[i].charAt(0)).append('.');
        }
        return sb.toString();
    }

    private static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        }
        try {
            return YearMonth.parse(month);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month phải có dạng YYYY-MM");
        }
    }

    private static Instant monthStart(YearMonth ym) {
        return ym.atDay(1).atStartOfDay(VIETNAM).toInstant();
    }

    private static Instant monthEnd(YearMonth ym) {
        return ym.plusMonths(1).atDay(1).atStartOfDay(VIETNAM).toInstant();
    }
}
