package com.metroticket_api.payment;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SepayTransactionRepository extends JpaRepository<SepayTransaction, Long> {

    Page<SepayTransaction> findByResultOrderByReceivedAtDesc(SepayResult result, Pageable pageable);

    Page<SepayTransaction> findAllByOrderByReceivedAtDesc(Pageable pageable);

    /** Tiền vào trong kỳ, bỏ các dòng không thuộc tài khoản thụ hưởng hoặc tiền ra. */
    @Query("""
            select t from SepayTransaction t
            where t.receivedAt >= :from and t.receivedAt < :to and t.result <> com.metroticket_api.payment.SepayResult.IGNORED
            order by t.receivedAt
            """)
    List<SepayTransaction> findIncomingInPeriod(Instant from, Instant to);
}
