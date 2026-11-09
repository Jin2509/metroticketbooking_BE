package com.metroticket_api.loyalty;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccount, Long> {

    Optional<LoyaltyAccount> findByUserId(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LoyaltyAccount a where a.userId = :userId")
    Optional<LoyaltyAccount> findByUserIdForUpdate(UUID userId);

    /** Tạo tài khoản điểm nếu chưa có; hai request đồng thời không gây lỗi trùng. */
    @Modifying
    @Query(value = "INSERT INTO loyalty_account (user_id) VALUES (:userId) ON CONFLICT (user_id) DO NOTHING", nativeQuery = true)
    void insertIfAbsent(UUID userId);
}
