package com.metroticket_api.ticket;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.ticket.TicketDtos.GateInspectRequest;
import com.metroticket_api.ticket.TicketDtos.GateInspectResponse;
import com.metroticket_api.ticket.TicketDtos.GateScanRequest;
import com.metroticket_api.ticket.TicketDtos.GateValidationRequest;
import com.metroticket_api.ticket.TicketDtos.GateValidationResponse;
import com.metroticket_api.ticket.TicketDtos.PurchaseTicketRequest;
import com.metroticket_api.ticket.TicketDtos.TicketResponse;
import com.metroticket_api.ticket.TicketDtos.ValidationResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Tickets", description = "Mua vé, vé của tôi, mã QR, nhân viên check-in/check-out tại cổng")
public class TicketController {

    private final TicketService tickets;
    private final GateService gates;

    public TicketController(TicketService tickets, GateService gates) {
        this.tickets = tickets;
        this.gates = gates;
    }

    @Operation(summary = "Mua vé", description = "Vé theo thời gian (periods = số tháng với vé tháng) hoặc vé lượt (originStationId, destinationStationId; giá theo km). Thanh toán SEPAY_QR.")
    @PreAuthorize("hasAuthority('TICKET_BUY')")
    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketResponse purchase(@Valid @RequestBody PurchaseTicketRequest request) {
        return tickets.purchase(request);
    }

    @PreAuthorize("hasAuthority('TICKET_VIEW_OWN')")
    @GetMapping("/me/tickets")
    public Page<TicketResponse> myTickets(@RequestParam(required = false) TicketStatus status,
            @RequestParam(required = false) String productCode,
            @PageableDefault(size = 20) Pageable pageable) {
        return tickets.myTickets(status, productCode, pageable);
    }

    /** Chủ vé hoặc người có TICKET_VIEW_ALL. */
    @GetMapping("/tickets/{ticketId}")
    public TicketResponse get(@PathVariable UUID ticketId) {
        return tickets.get(ticketId);
    }

    @Operation(summary = "Ảnh mã QR của vé (PNG)", description = "Chỉ chủ vé, vé đã thanh toán và còn dùng được. size: 128–1024 px.")
    @PreAuthorize("hasAuthority('TICKET_VIEW_OWN')")
    @GetMapping("/tickets/{ticketId}/qr")
    public ResponseEntity<byte[]> qr(@PathVariable UUID ticketId,
            @RequestParam(defaultValue = "" + QrCodeRenderer.DEFAULT_SIZE) int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG)
                .body(tickets.qrImage(ticketId, size));
    }

    @GetMapping("/tickets/{ticketId}/validations")
    public List<ValidationResponse> history(@PathVariable UUID ticketId) {
        return tickets.history(ticketId);
    }

    @PreAuthorize("hasAuthority('TICKET_VIEW_ALL')")
    @GetMapping("/admin/tickets")
    public Page<TicketResponse> search(@RequestParam(required = false) TicketStatus status,
            @RequestParam(required = false) String productCode,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String ticketCode,
            @PageableDefault(size = 20, sort = "purchasedAt", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
        return tickets.search(status, productCode, userId, ticketCode, pageable);
    }

    @Operation(summary = "Nhân viên quét QR: xem trước vé", description = "Cho biết thao tác tiếp theo (check-in/check-out), kết quả dự kiến và số tiền cần thu bù. Không đổi trạng thái vé.")
    @PreAuthorize("hasAuthority('TICKET_VALIDATE')")
    @PostMapping("/gates/inspect")
    public GateInspectResponse inspect(@Valid @RequestBody GateInspectRequest request) {
        return gates.inspect(request.qrToken(), request.stationId());
    }

    @Operation(summary = "Nhân viên check-in hành khách (quẹt vào)", description = "Từ chối vẫn trả 200 với result=REJECTED.")
    @PreAuthorize("hasAuthority('TICKET_VALIDATE')")
    @PostMapping("/gates/check-in")
    public GateValidationResponse checkIn(@Valid @RequestBody GateScanRequest request) {
        return gates.validate(request.toValidation(GateDirection.ENTRY));
    }

    @Operation(summary = "Nhân viên check-out hành khách (quẹt ra)", description = "Vé lượt ra quá ga đã mua: gửi fareAdjustmentMethod (CASH...) để thu bù; bỏ trống thì bị từ chối kèm số tiền.")
    @PreAuthorize("hasAuthority('TICKET_VALIDATE')")
    @PostMapping("/gates/check-out")
    public GateValidationResponse checkOut(@Valid @RequestBody GateScanRequest request) {
        return gates.validate(request.toValidation(GateDirection.EXIT));
    }

    @Operation(summary = "Soát vé tại cổng", description = "Nhân viên phải được phân công tại ga (hoặc có STATION_MANAGE). Từ chối vẫn trả 200 với result=REJECTED.")
    @PreAuthorize("hasAuthority('TICKET_VALIDATE')")
    @PostMapping("/gates/validate")
    public GateValidationResponse validate(@Valid @RequestBody GateValidationRequest request) {
        return gates.validate(request);
    }
}
