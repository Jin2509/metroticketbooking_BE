package com.metroticket_api.parking;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.metroticket_api.payment.PaymentFulfiller;
import com.metroticket_api.payment.PaymentOrder;
import com.metroticket_api.payment.PaymentPurpose;

/**
 * Tiền về → kích hoạt vé tháng, thời hạn 30 ngày tính từ lúc thanh toán. Kiểm tra lại trùng biển số và suất vé tháng
 * vì trong lúc chờ thanh toán có thể đã có vé khác được kích hoạt.
 */
@Component
public class ParkingPassPaymentFulfiller implements PaymentFulfiller {

    private final ParkingMonthlyPassRepository passes;
    private final ParkingRateRepository rates;

    public ParkingPassPaymentFulfiller(ParkingMonthlyPassRepository passes, ParkingRateRepository rates) {
        this.passes = passes;
        this.rates = rates;
    }

    @Override
    public PaymentPurpose purpose() {
        return PaymentPurpose.PARKING_MONTHLY_PASS;
    }

    @Override
    public String fulfil(PaymentOrder order, boolean late, Instant now) {
        ParkingMonthlyPass pass = passes.findById(UUID.fromString(order.getReferenceId())).orElse(null);
        if (pass == null) {
            return "Không tìm thấy vé tháng của đơn";
        }
        boolean pending = pass.getStatus() == MonthlyPassStatus.PENDING_PAYMENT;
        boolean recoverable = late && pass.getStatus() == MonthlyPassStatus.CANCELLED;
        if (!pending && !recoverable) {
            return "Vé tháng không ở trạng thái chờ thanh toán (" + pass.getStatus() + ")";
        }
        Instant until = now.plus(ParkingService.MONTHLY_PASS_DURATION);
        ParkingRate rate = rates.findForUpdate(pass.getLot().getId(), pass.getVehicleType()).orElse(null);
        if (rate == null) {
            return "Bãi không còn nhận loại xe " + pass.getVehicleType();
        }
        if (passes.countOverlapping(pass.getLot().getId(), pass.getPlateNumber(), now, until) > 0) {
            return "Xe đã có vé tháng khác còn hạn tại bãi";
        }
        if (passes.countActive(pass.getLot().getId(), pass.getVehicleType(), now) >= rate.getCapacity()) {
            return "Bãi đã hết suất vé tháng";
        }
        pass.activate(now, until);
        return null;
    }

    @Override
    public void release(PaymentOrder order) {
        passes.findById(UUID.fromString(order.getReferenceId()))
                .filter(p -> p.getStatus() == MonthlyPassStatus.PENDING_PAYMENT)
                .ifPresent(ParkingMonthlyPass::cancel);
    }
}
