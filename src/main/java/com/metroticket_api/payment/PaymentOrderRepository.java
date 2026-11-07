package com.metroticket_api.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, UUID> {

    boolean existsByOrderCode(String orderCode);

    Optional<PaymentOrder> findByOrderCode(String orderCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PaymentOrder o where o.orderCode = :code")
    Optional<PaymentOrder> findByOrderCodeForUpdate(@Param("code") String code);

    Optional<PaymentOrder> findByPurposeAndReferenceId(PaymentPurpose purpose, String referenceId);

    long countByUserIdAndStatus(UUID userId, PaymentOrderStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PaymentOrder o where o.status = com.metroticket_api.payment.PaymentOrderStatus.PENDING "
            + "and o.expiresAt <= :now")
    List<PaymentOrder> findExpiredPendingForUpdate(@Param("now") Instant now);

    Page<PaymentOrder> findByStatusOrderByCreatedAtDesc(PaymentOrderStatus status, Pageable pageable);

    Page<PaymentOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
