package com.metroticket_api.notification.campaign;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Dựng truy vấn người nhận và chèn thông báo/hàng đợi gửi hàng loạt bằng INSERT … SELECT: không nạp danh sách người
 * dùng vào bộ nhớ dù gửi cho toàn hệ thống. Khuyến mãi bỏ qua người đã tắt nhận khuyến mãi.
 */
@Component
public class CampaignAudience {

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    /** LINE/STATION: người đã quẹt vé thành công tại ga trong khoảng này. */
    static final Duration RECENT_TRAVEL = Duration.ofDays(30);

    private final NamedParameterJdbcTemplate jdbc;

    public CampaignAudience(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Câu SELECT trả về cột id của người nhận. */
    static String audienceSql(AudienceType type) {
        String base = "SELECT u.id FROM app_user u WHERE u.status = 'ACTIVE'";
        return switch (type) {
            case ALL -> base;
            case ROLE -> base + " AND EXISTS (SELECT 1 FROM user_role ur JOIN role r ON r.id = ur.role_id "
                    + "WHERE ur.user_id = u.id AND r.code = :value)";
            case STUDENTS -> base + " AND EXISTS (SELECT 1 FROM student_verification v WHERE v.user_id = u.id "
                    + "AND v.status = 'APPROVED' AND v.approved_until >= :today)";
            case ACTIVE_PASS_HOLDERS -> base + " AND EXISTS (SELECT 1 FROM ticket t WHERE t.user_id = u.id "
                    + "AND t.status IN ('ACTIVE', 'IN_USE') AND t.valid_until > :now)";
            case STATION -> base + " AND EXISTS (SELECT 1 FROM ticket_validation tv JOIN ticket t ON t.id = tv.ticket_id "
                    + "WHERE t.user_id = u.id AND tv.result = 'ACCEPTED' AND tv.validated_at >= :since "
                    + "AND tv.station_id = CAST(:value AS BIGINT))";
            case LINE -> base + " AND EXISTS (SELECT 1 FROM ticket_validation tv JOIN ticket t ON t.id = tv.ticket_id "
                    + "WHERE t.user_id = u.id AND tv.result = 'ACCEPTED' AND tv.validated_at >= :since "
                    + "AND tv.station_id IN (SELECT ls.station_id FROM line_station ls WHERE ls.line_id = CAST(:value AS BIGINT)))";
            case USERS -> base + " AND EXISTS (SELECT 1 FROM notification_campaign_user cu "
                    + "WHERE cu.campaign_id = :campaignId AND cu.user_id = u.id)";
        };
    }

    private static String recipientsSql(AudienceType type) {
        return "SELECT a.id FROM (" + audienceSql(type) + ") a LEFT JOIN notification_preference p ON p.user_id = a.id "
                + "WHERE (:type <> 'PROMOTION' OR COALESCE(p.promotion_enabled, TRUE))";
    }

    private static MapSqlParameterSource params(NotificationCampaign c, Instant now) {
        return new MapSqlParameterSource()
                .addValue("campaignId", c.getId())
                .addValue("value", c.getAudienceValue())
                .addValue("type", c.getType().name())
                .addValue("now", Timestamp.from(now))
                .addValue("since", Timestamp.from(now.minus(RECENT_TRAVEL)))
                .addValue("today", Date.valueOf(LocalDate.ofInstant(now, VIETNAM)));
    }

    public int count(NotificationCampaign c, Instant now) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM (" + recipientsSql(c.getAudienceType()) + ") r",
                params(c, now), Integer.class);
        return n == null ? 0 : n;
    }

    /** Tạo thông báo trong app cho mọi người nhận; trả về số người. */
    public int insertNotifications(NotificationCampaign c, Instant now) {
        String sql = "INSERT INTO notification (id, user_id, type, title, body, created_at, priority, link, campaign_id) "
                + "SELECT gen_random_uuid(), r.id, :type, :title, :body, :now, :priority, :link, :campaignId FROM ("
                + recipientsSql(c.getAudienceType()) + ") r";
        return jdbc.update(sql, params(c, now)
                .addValue("title", c.getTitle())
                .addValue("body", c.getBody())
                .addValue("priority", c.getPriority().name())
                .addValue("link", c.getLink()));
    }

    /** Xếp hàng push cho mọi thiết bị đang hoạt động của người nhận đã bật push. */
    public int queuePush(NotificationCampaign c, Instant now) {
        return jdbc.update("""
                INSERT INTO notification_delivery (notification_id, user_id, channel, device_id, status, attempts,
                                                   next_attempt_at, created_at)
                SELECT n.id, n.user_id, 'PUSH', d.id, 'PENDING', 0, :now, :now
                FROM notification n
                JOIN user_device d ON d.user_id = n.user_id AND d.disabled_at IS NULL
                LEFT JOIN notification_preference p ON p.user_id = n.user_id
                WHERE n.campaign_id = :campaignId AND COALESCE(p.push_enabled, TRUE)""", params(c, now));
    }

    public int queueEmail(NotificationCampaign c, Instant now) {
        return jdbc.update("""
                INSERT INTO notification_delivery (notification_id, user_id, channel, status, attempts,
                                                   next_attempt_at, created_at)
                SELECT n.id, n.user_id, 'EMAIL', 'PENDING', 0, :now, :now
                FROM notification n
                LEFT JOIN notification_preference p ON p.user_id = n.user_id
                WHERE n.campaign_id = :campaignId AND COALESCE(p.email_enabled, TRUE)""", params(c, now));
    }

    public void addUsers(NotificationCampaign c, java.util.Collection<java.util.UUID> userIds) {
        jdbc.update("DELETE FROM notification_campaign_user WHERE campaign_id = :campaignId",
                new MapSqlParameterSource("campaignId", c.getId()));
        MapSqlParameterSource[] batch = userIds.stream()
                .map(u -> new MapSqlParameterSource().addValue("campaignId", c.getId()).addValue("userId", u))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("INSERT INTO notification_campaign_user (campaign_id, user_id) VALUES (:campaignId, :userId) "
                + "ON CONFLICT DO NOTHING", batch);
    }
}
