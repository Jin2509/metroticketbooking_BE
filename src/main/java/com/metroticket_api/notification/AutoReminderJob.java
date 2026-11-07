package com.metroticket_api.notification;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.metroticket_api.notification.NotificationDispatcher.Request;

/**
 * Nhắc nhở tự động, chạy mỗi giờ. Mỗi nhắc nhở có dedupe_key nên chỉ gửi một lần dù job chạy lại hay nhiều instance.
 * Người dùng tắt "nhắc nhở" (reminderEnabled=false) thì không nhận (trừ nhắc hạn xác thực sinh viên).
 *
 * <ul>
 *   <li>Vé đã kích hoạt sắp hết hạn: vé ngày trước 2 giờ, vé tuần trước 1 ngày, vé tháng trước 3 ngày.</li>
 *   <li>Vé chưa kích hoạt sắp hết hạn kích hoạt: trước 3 ngày.</li>
 *   <li>Vé tháng gửi xe sắp hết hạn: trước 3 ngày.</li>
 *   <li>Ưu đãi sinh viên sắp hết hạn: trước 14 ngày (nhắc nộp lại hồ sơ).</li>
 * </ul>
 */
@Component
public class AutoReminderJob {

    private static final Logger log = LoggerFactory.getLogger(AutoReminderJob.class);
    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(VIETNAM);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationDispatcher dispatcher;
    private final Clock clock;

    public AutoReminderJob(NamedParameterJdbcTemplate jdbc, NotificationDispatcher dispatcher, Clock clock) {
        this.jdbc = jdbc;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.notification.reminder-cron:0 5 * * * *}", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public int run() {
        Instant now = clock.instant();
        int sent = passesExpiring(now) + activationDeadlines(now) + parkingPassesExpiring(now) + studentExpiring(now);
        if (sent > 0) {
            log.info("Đã gửi {} nhắc nhở tự động", sent);
        }
        return sent;
    }

    int passesExpiring(Instant now) {
        String sql = """
                SELECT t.id, t.user_id, t.ticket_code, p.name, t.valid_until FROM ticket t
                JOIN ticket_product p ON p.id = t.product_id
                WHERE t.status IN ('ACTIVE', 'IN_USE') AND t.activated_at IS NOT NULL AND t.valid_until > :now
                  AND t.valid_until <= CAST(:now AS timestamptz) + (CASE t.duration_unit WHEN 'DAY' THEN INTERVAL '2 hours'
                                                                     WHEN 'WEEK' THEN INTERVAL '1 day'
                                                                     ELSE INTERVAL '3 days' END)""";
        return jdbc.query(sql, new MapSqlParameterSource("now", Timestamp.from(now)), (rs, i) -> dispatch(
                Request.of((UUID) rs.getObject("user_id"), NotificationType.TICKET, "Vé sắp hết hạn",
                        rs.getString("name") + " (" + rs.getString("ticket_code") + ") hết hạn lúc "
                                + WHEN.format(rs.getTimestamp("valid_until").toInstant()) + ". Gia hạn để không gián đoạn.")
                        .link("/tickets/" + rs.getObject("id")).dedupe("TICKET_EXPIRING:" + rs.getObject("id"))))
                .stream().mapToInt(Integer::intValue).sum();
    }

    int activationDeadlines(Instant now) {
        String sql = """
                SELECT t.id, t.user_id, t.ticket_code, p.name, t.valid_until FROM ticket t
                JOIN ticket_product p ON p.id = t.product_id
                WHERE t.status = 'ACTIVE' AND t.activated_at IS NULL AND t.valid_until > :now
                  AND t.valid_until <= :until""";
        var params = new MapSqlParameterSource("now", Timestamp.from(now))
                .addValue("until", Timestamp.from(now.plus(Duration.ofDays(3))));
        return jdbc.query(sql, params, (rs, i) -> dispatch(
                Request.of((UUID) rs.getObject("user_id"), NotificationType.TICKET, "Vé chưa sử dụng sắp hết hạn kích hoạt",
                        rs.getString("name") + " (" + rs.getString("ticket_code") + ") phải được quẹt vào lần đầu trước "
                                + WHEN.format(rs.getTimestamp("valid_until").toInstant())
                                + ", hoặc hoàn vé nếu không dùng.")
                        .link("/tickets/" + rs.getObject("id")).dedupe("TICKET_ACTIVATION:" + rs.getObject("id"))))
                .stream().mapToInt(Integer::intValue).sum();
    }

    int parkingPassesExpiring(Instant now) {
        String sql = """
                SELECT pp.id, pp.user_id, pp.plate_number, l.name, pp.valid_until FROM parking_monthly_pass pp
                JOIN parking_lot l ON l.id = pp.lot_id
                WHERE pp.status = 'ACTIVE' AND pp.valid_until > :now AND pp.valid_until <= :until""";
        var params = new MapSqlParameterSource("now", Timestamp.from(now))
                .addValue("until", Timestamp.from(now.plus(Duration.ofDays(3))));
        return jdbc.query(sql, params, (rs, i) -> dispatch(
                Request.of((UUID) rs.getObject("user_id"), NotificationType.PARKING, "Vé tháng gửi xe sắp hết hạn",
                        "Xe " + rs.getString("plate_number") + " tại " + rs.getString("name") + " hết hạn lúc "
                                + WHEN.format(rs.getTimestamp("valid_until").toInstant()) + ".")
                        .link("/parking/passes/" + rs.getObject("id")).dedupe("PARKING_PASS_EXPIRING:" + rs.getObject("id"))))
                .stream().mapToInt(Integer::intValue).sum();
    }

    int studentExpiring(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, VIETNAM);
        String sql = """
                SELECT v.id, v.user_id, v.approved_until FROM student_verification v
                WHERE v.status = 'APPROVED' AND v.approved_until >= :today AND v.approved_until <= :until
                  AND NOT EXISTS (SELECT 1 FROM student_verification o WHERE o.user_id = v.user_id
                                  AND o.submitted_at > v.submitted_at
                                  AND o.status IN ('PENDING', 'PROCESSING', 'NEEDS_REVIEW', 'APPROVED'))""";
        var params = new MapSqlParameterSource("today", Date.valueOf(today)).addValue("until", Date.valueOf(today.plusDays(14)));
        return jdbc.query(sql, params, (rs, i) -> dispatch(
                Request.of((UUID) rs.getObject("user_id"), NotificationType.STUDENT, "Ưu đãi sinh viên sắp hết hạn",
                        "Ưu đãi của bạn hết hạn ngày " + DAY.format(rs.getDate("approved_until").toLocalDate())
                                + ". Hãy nộp lại ảnh thẻ sinh viên mới để tiếp tục được giảm giá.")
                        .link("/student").dedupe("STUDENT_EXPIRING:" + rs.getObject("id"))))
                .stream().mapToInt(Integer::intValue).sum();
    }

    private int dispatch(Request r) {
        return dispatcher.dispatch(r).isPresent() ? 1 : 0;
    }
}
