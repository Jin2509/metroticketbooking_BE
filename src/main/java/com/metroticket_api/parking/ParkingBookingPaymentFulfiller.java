package com.metroticket_api.parking;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.metroticket_api.payment.PaymentFulfiller;
import com.metroticket_api.payment.PaymentOrder;
import com.metroticket_api.payment.PaymentPurpose;

/** Tiền về → lượt đặt chỗ sang "Chưa gửi"; đơn hết hạn/huỷ → huỷ lượt và trả chỗ. */
@Component
public class ParkingBookingPaymentFulfiller implements PaymentFulfiller {

    private final ParkingBookingRepository bookings;

    public ParkingBookingPaymentFulfiller(ParkingBookingRepository bookings) {
        this.bookings = bookings;
    }

    @Override
    public PaymentPurpose purpose() {
        return PaymentPurpose.PARKING_BOOKING;
    }

    @Override
    public String fulfil(PaymentOrder order, Instant now) {
        ParkingBooking booking = bookings.findByIdForUpdate(UUID.fromString(order.getReferenceId())).orElse(null);
        if (booking == null) {
            return "Không tìm thấy lượt đặt chỗ của đơn";
        }
        if (booking.getStatus() != ParkingBookingStatus.PENDING_PAYMENT) {
            return "Lượt đặt chỗ không ở trạng thái chờ thanh toán (" + booking.getStatus() + ")";
        }
        booking.markPaid();
        return null;
    }

    @Override
    public void release(PaymentOrder order) {
        bookings.findByIdForUpdate(UUID.fromString(order.getReferenceId())).ifPresent(ParkingBooking::cancelUnpaid);
    }
}
