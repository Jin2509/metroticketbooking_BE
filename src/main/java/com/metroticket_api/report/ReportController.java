package com.metroticket_api.report;

import java.time.LocalDate;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.audit.AuditLogService;
import com.metroticket_api.report.ReportService.Report;
import com.metroticket_api.report.ReportService.RevenueGroup;
import com.metroticket_api.report.ReportService.TrafficGroup;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports", description = "UC23: thống kê và xuất báo cáo")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final ReportService reports;
    private final ReportExporter exporter;
    private final AuditLogService audit;

    public ReportController(ReportService reports, ReportExporter exporter, AuditLogService audit) {
        this.reports = reports;
        this.exporter = exporter;
        this.audit = audit;
    }

    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    @GetMapping("/revenue")
    public Report revenue(@RequestParam LocalDate from, @RequestParam LocalDate to,
            @RequestParam(defaultValue = "DAY") RevenueGroup groupBy) {
        audit.log("REPORT_VIEW", null, null, "Doanh thu " + groupBy + " " + from + " đến " + to);
        return reports.revenue(from, to, groupBy);
    }

    @PreAuthorize("hasAuthority('REPORT_TRAFFIC_VIEW')")
    @GetMapping("/traffic")
    public Report traffic(@RequestParam LocalDate from, @RequestParam LocalDate to,
            @RequestParam(defaultValue = "STATION") TrafficGroup groupBy) {
        audit.log("REPORT_VIEW", null, null, "Lưu lượng " + groupBy + " " + from + " đến " + to);
        return reports.traffic(from, to, groupBy);
    }

    @PreAuthorize("hasAuthority('REPORT_EXPORT')")
    @GetMapping("/revenue/export")
    public ResponseEntity<byte[]> exportRevenue(@RequestParam LocalDate from, @RequestParam LocalDate to,
            @RequestParam(defaultValue = "DAY") RevenueGroup groupBy, @RequestParam(defaultValue = "xlsx") String format) {
        return export(reports.revenue(from, to, groupBy), "doanh-thu", format);
    }

    @PreAuthorize("hasAuthority('REPORT_EXPORT') and hasAuthority('REPORT_TRAFFIC_VIEW')")
    @GetMapping("/traffic/export")
    public ResponseEntity<byte[]> exportTraffic(@RequestParam LocalDate from, @RequestParam LocalDate to,
            @RequestParam(defaultValue = "STATION") TrafficGroup groupBy, @RequestParam(defaultValue = "xlsx") String format) {
        return export(reports.traffic(from, to, groupBy), "luu-luong", format);
    }

    private ResponseEntity<byte[]> export(Report report, String name, String rawFormat) {
        String format = rawFormat.toLowerCase(Locale.ROOT);
        if (!format.equals("xlsx") && !format.equals("pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Định dạng tệp phải là xlsx hoặc pdf");
        }
        byte[] content;
        try {
            content = format.equals("pdf") ? exporter.pdf(report) : exporter.excel(report);
        } catch (Exception e) {
            log.error("Không tạo được tệp báo cáo", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Xuất báo cáo thất bại, vui lòng thử lại");
        }
        audit.log("REPORT_EXPORT", null, null, report.title() + " " + report.from() + " đến " + report.to() + " (" + format + ")");
        MediaType type = format.equals("pdf") ? MediaType.APPLICATION_PDF
                : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(name + "-" + report.from() + "-" + report.to() + "." + format).build().toString())
                .body(content);
    }
}
