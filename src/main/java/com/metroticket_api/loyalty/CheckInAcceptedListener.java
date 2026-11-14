package com.metroticket_api.loyalty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tích điểm sau khi lượt soát vé đã commit: lỗi tích điểm không được làm hỏng thao tác của nhân viên (UC15).
 * Exception từ listener AFTER_COMMIT bị Spring ném ngược lên request soát vé (đã commit), nên tích điểm chạy trong
 * transaction riêng và lỗi (kể cả lỗi lúc commit) chỉ được ghi log.
 */
@Component
class CheckInAcceptedListener {

    private static final Logger log = LoggerFactory.getLogger(CheckInAcceptedListener.class);

    private final LoyaltyService loyalty;
    private final TransactionTemplate newTransaction;

    CheckInAcceptedListener(LoyaltyService loyalty, PlatformTransactionManager transactionManager) {
        this.loyalty = loyalty;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(CheckInAccepted event) {
        try {
            newTransaction.executeWithoutResult(status -> loyalty.earnForCheckIn(event.userId(), event.validationId()));
        } catch (RuntimeException e) {
            log.warn("Không tích được CPoint cho lượt check-in {} của {}: {}", event.validationId(), event.userId(),
                    e.toString());
        }
    }
}
