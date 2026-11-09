package com.metroticket_api.loyalty;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransaction, Long> {

    boolean existsByValidationIdAndType(Long validationId, LoyaltyTransaction.Type type);

    boolean existsByReferenceTypeAndReferenceIdAndType(String referenceType, String referenceId, LoyaltyTransaction.Type type);

    long countByUserIdAndTypeAndCreatedAtGreaterThanEqual(UUID userId, LoyaltyTransaction.Type type, Instant from);

    Page<LoyaltyTransaction> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Pageable pageable);
}
