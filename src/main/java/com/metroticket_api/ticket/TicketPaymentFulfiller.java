package com.metroticket_api.ticket;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.metroticket_api.payment.PaymentFulfiller;
import com.metroticket_api.payment.PaymentOrder;
import com.metroticket_api.payment.PaymentPurpose;

/** Tiền về → kích hoạt vé; đơn hết hạn/huỷ → huỷ vé đang chờ thanh toán. */
@Component
public class TicketPaymentFulfiller implements PaymentFulfiller {

    private final TicketRepository tickets;

    public TicketPaymentFulfiller(TicketRepository tickets) {
        this.tickets = tickets;
    }

    @Override
    public PaymentPurpose purpose() {
        return PaymentPurpose.TICKET;
    }

    @Override
    public String fulfil(PaymentOrder order, boolean late, Instant now) {
        Ticket ticket = tickets.findByIdForUpdate(UUID.fromString(order.getReferenceId())).orElse(null);
        if (ticket == null) {
            return "Không tìm thấy vé của đơn";
        }
        if (ticket.getPriceVnd() != order.getAmountVnd()) {
            return "Số tiền đơn không khớp giá vé";
        }
        return ticket.confirmPayment(late, now) ? null
                : "Vé không còn kích hoạt được (" + ticket.getStatus() + ", hạn " + ticket.getValidUntil() + ")";
    }

    @Override
    public void release(PaymentOrder order) {
        tickets.findByIdForUpdate(UUID.fromString(order.getReferenceId())).ifPresent(Ticket::cancelUnpaid);
    }
}
