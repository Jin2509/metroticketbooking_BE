package com.metroticket_api.payment;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    List<PaymentTransaction> findByPurposeAndReferenceIdAndStatus(PaymentPurpose purpose, String referenceId,
            PaymentStatus status);

    Page<PaymentTransaction> findByStatusOrderByCreatedAtDesc(PaymentStatus status, Pageable pageable);
}
