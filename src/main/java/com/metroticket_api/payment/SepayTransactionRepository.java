package com.metroticket_api.payment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SepayTransactionRepository extends JpaRepository<SepayTransaction, Long> {

    Page<SepayTransaction> findByResultOrderByReceivedAtDesc(SepayResult result, Pageable pageable);

    Page<SepayTransaction> findAllByOrderByReceivedAtDesc(Pageable pageable);
}
