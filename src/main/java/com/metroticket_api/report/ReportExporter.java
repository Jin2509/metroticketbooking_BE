package com.metroticket_api.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

/** Tạo tệp Excel và PDF từ đúng số liệu hiển thị trên màn hình. PDF nhúng font DejaVu để hiện đủ tiếng Việt. */
@Component
public class ReportExporter {

    private static final String FONT_PATH = "/net/sf/jasperreports/fonts/dejavu/DejaVuSans.ttf";

    public byte[] excel(ReportService.Report report) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Báo cáo");
            int r = 0;
            sheet.createRow(r++).createCell(0).setCellValue(report.title() + " (" + report.from() + " đến " + report.to() + ")");
            boolean money = report.totalAmountVnd() != null;
            Row header = sheet.createRow(r++);
            header.createCell(0).setCellValue("Nhóm");
            header.createCell(1).setCellValue("Số lượng");
            if (money) header.createCell(2).setCellValue("Số tiền (đ)");
            for (ReportService.Row row : report.rows()) {
                Row line = sheet.createRow(r++);
                line.createCell(0).setCellValue(row.label());
                line.createCell(1).setCellValue(row.count());
                if (money) line.createCell(2).setCellValue(row.amountVnd());
            }
            Row total = sheet.createRow(r);
            total.createCell(0).setCellValue("Tổng");
            total.createCell(1).setCellValue(report.totalCount());
            if (money) total.createCell(2).setCellValue(report.totalAmountVnd());
            sheet.setColumnWidth(0, 40 * 256);
            sheet.setColumnWidth(1, 14 * 256);
            sheet.setColumnWidth(2, 18 * 256);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    public byte[] pdf(ReportService.Report report) throws IOException, DocumentException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document();
        PdfWriter.getInstance(document, out);
        document.open();
        byte[] fontBytes;
        try (InputStream in = ReportExporter.class.getResourceAsStream(FONT_PATH)) {
            if (in == null) {
                throw new IOException("Thiếu font " + FONT_PATH);
            }
            fontBytes = in.readAllBytes();
        }
        BaseFont base = BaseFont.createFont("DejaVuSans.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, fontBytes, null);
        Font font = new Font(base, 10);
        document.add(new Paragraph(report.title() + " (" + report.from() + " đến " + report.to() + ")", new Font(base, 13)));
        document.add(new Paragraph(" ", font));
        boolean money = report.totalAmountVnd() != null;
        PdfPTable table = new PdfPTable(money ? 3 : 2);
        table.setWidthPercentage(100);
        addRow(table, font, money, "Nhóm", "Số lượng", "Số tiền (đ)");
        for (ReportService.Row row : report.rows()) {
            addRow(table, font, money, row.label(), Long.toString(row.count()), String.valueOf(row.amountVnd()));
        }
        addRow(table, font, money, "Tổng", Long.toString(report.totalCount()), String.valueOf(report.totalAmountVnd()));
        document.add(table);
        document.close();
        return out.toByteArray();
    }

    private static void addRow(PdfPTable table, Font font, boolean money, String label, String count, String amount) {
        table.addCell(new com.lowagie.text.Phrase(label, font));
        table.addCell(new com.lowagie.text.Phrase(count, font));
        if (money) {
            table.addCell(new com.lowagie.text.Phrase(amount, font));
        }
    }
}
