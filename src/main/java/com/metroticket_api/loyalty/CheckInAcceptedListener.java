package com.metroticket_api.loyalty;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Tích điểm sau khi lượt soát vé đã commit: lỗi tích điểm không được làm hỏng thao tác của nhân viên (UC15). */
@Component
class CheckInAcceptedListener {

    private final LoyaltyService loyalty;

    CheckInAcceptedListener(LoyaltyService loyalty) {
        this.loyalty = loyalty;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(CheckInAccepted event) {
        loyalty.earnForCheckIn(event.userId(), event.validationId());
    }
}
