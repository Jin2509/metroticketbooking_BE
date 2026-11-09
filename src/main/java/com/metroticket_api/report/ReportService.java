package com.metroticket_api.report;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Thống kê doanh thu và lưu lượng (UC23). Lưu lượng chỉ đếm lượt vào ga, không chứa thông tin cá nhân. */
@Service
public class ReportService {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String LOCAL_TIME = " AT TIME ZONE 'Asia/Ho_Chi_Minh'";
    static final int MAX_DAYS = 366;

    public enum RevenueGroup { DAY, MONTH, PRODUCT, SERVICE }

    public enum TrafficGroup { STATION, LINE, HOUR }

    public record Row(String label, long count, Long amountVnd) {
    }

    public record Report(String title, LocalDate from, LocalDate to, long totalCount, Long totalAmountVnd, List<Row> rows) {
    }

    private final JdbcTemplate jdbc;

    public ReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Report revenue(LocalDate from, LocalDate to, RevenueGroup group) {
        validate(from, to);
        String label = switch (group) {
            case DAY -> "to_char(pt.created_at" + LOCAL_TIME + ", 'YYYY-MM-DD')";
            case MONTH -> "to_char(pt.created_at" + LOCAL_TIME + ", 'YYYY-MM')";
            case PRODUCT -> "coalesce(tp.name, pt.purpose)";
            case SERVICE -> "pt.purpose";
        };
        String join = group == RevenueGroup.PRODUCT
                ? " LEFT JOIN ticket t ON pt.purpose = 'TICKET' AND t.id::text = pt.reference_id"
                        + " LEFT JOIN ticket_product tp ON tp.id = t.product_id"
                : "";
        String sql = "SELECT " + label + " AS label, count(*) AS c, coalesce(sum(pt.amount_vnd), 0) AS a"
                + " FROM payment_transaction pt" + join
                + " WHERE pt.status = 'SUCCEEDED' AND pt.created_at >= ? AND pt.created_at < ?"
                + " GROUP BY 1 ORDER BY 1";
        List<Row> rows = jdbc.query(sql, (rs, i) -> new Row(rs.getString("label"), rs.getLong("c"), rs.getLong("a")),
                start(from), end(to));
        long count = rows.stream().mapToLong(Row::count).sum();
        long amount = rows.stream().mapToLong(Row::amountVnd).sum();
        return new Report("Doanh thu theo " + group, from, to, count, amount, rows);
    }

    @Transactional(readOnly = true)
    public Report traffic(LocalDate from, LocalDate to, TrafficGroup group) {
        validate(from, to);
        String label;
        String join;
        switch (group) {
            case STATION -> {
                label = "s.name_station";
                join = " JOIN station s ON s.id = tv.station_id";
            }
            case LINE -> {
                label = "l.name_line";
                join = " JOIN line_station ls ON ls.station_id = tv.station_id JOIN metro_line l ON l.id = ls.line_id";
            }
            default -> {
                label = "to_char(tv.validated_at" + LOCAL_TIME + ", 'HH24')";
                join = "";
            }
        }
        String sql = "SELECT " + label + " AS label, count(*) AS c FROM ticket_validation tv" + join
                + " WHERE tv.result = 'ACCEPTED' AND tv.direction = 'ENTRY' AND tv.validated_at >= ? AND tv.validated_at < ?"
                + " GROUP BY 1 ORDER BY 1";
        List<Row> rows = jdbc.query(sql, (rs, i) -> new Row(rs.getString("label"), rs.getLong("c"), null), start(from), end(to));
        return new Report("Lưu lượng theo " + group, from, to, rows.stream().mapToLong(Row::count).sum(), null, rows);
    }

    private static void validate(LocalDate from, LocalDate to) {
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Khoảng thời gian không hợp lệ");
        }
    }

    private static Timestamp start(LocalDate from) {
        return Timestamp.from(from.atStartOfDay(VIETNAM).toInstant());
    }

    private static Timestamp end(LocalDate to) {
        return Timestamp.from(to.plusDays(1).atStartOfDay(VIETNAM).toInstant());
    }
}
