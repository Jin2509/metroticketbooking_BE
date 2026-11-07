package com.metroticket_api.ticket;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.ticket.TicketDtos.GateValidationRequest;
import com.metroticket_api.ticket.TicketDtos.GateValidationResponse;
import com.metroticket_api.ticket.TicketDtos.PurchaseTicketRequest;
import com.metroticket_api.ticket.TicketDtos.RefundResponse;
import com.metroticket_api.ticket.TicketDtos.TicketResponse;
import com.metroticket_api.ticket.TicketDtos.ValidationResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Tickets", description = "Mua vé, vé của tôi, hoàn vé, soát vé tại cổng")
public class TicketController {

    private final TicketService tickets;
    private final GateService gates;

    public TicketController(TicketService tickets, GateService gates) {
        this.tickets = tickets;
        this.gates = gates;
    }

    @Operation(summary = "Mua vé", description = "Vé theo thời gian; periods = số tháng với vé tháng. Thanh toán SEPAY_QR.")
    @PreAuthorize("hasAuthority('TICKET_BUY')")
    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketResponse purchase(@Valid @RequestBody PurchaseTicketRequest request) {
        return tickets.purchase(request);
    }

    @PreAuthorize("hasAuthority('TICKET_VIEW_OWN')")
    @GetMapping("/me/tickets")
    public Page<TicketResponse> myTickets(@RequestParam(required = false) TicketStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return tickets.myTickets(status, pageable);
    }

    /** Chủ vé hoặc người có TICKET_VIEW_ALL. */
    @GetMapping("/tickets/{ticketId}")
    public TicketResponse get(@PathVariable UUID ticketId) {
        return tickets.get(ticketId);
    }

    @GetMapping("/tickets/{ticketId}/validations")
    public List<ValidationResponse> history(@PathVariable UUID ticketId) {
        return tickets.history(ticketId);
    }

    @Operation(summary = "Hoàn vé", description = "Chỉ vé chưa sử dụng, chưa kích hoạt, còn hạn; chỉ chủ vé.")
    @PreAuthorize("hasAuthority('TICKET_BUY')")
    @PostMapping("/tickets/{ticketId}/refund")
    public RefundResponse refund(@PathVariable UUID ticketId) {
        return tickets.refund(ticketId);
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

    @Operation(summary = "Soát vé tại cổng", description = "Nhân viên phải được phân công tại ga (hoặc có STATION_MANAGE). Từ chối vẫn trả 200 với result=REJECTED.")
    @PreAuthorize("hasAuthority('TICKET_VALIDATE')")
    @PostMapping("/gates/validate")
    public GateValidationResponse validate(@Valid @RequestBody GateValidationRequest request) {
        return gates.validate(request);
    }
}
