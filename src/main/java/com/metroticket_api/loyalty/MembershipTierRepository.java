package com.metroticket_api.loyalty;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipTierRepository extends JpaRepository<MembershipTier, Long> {

    List<MembershipTier> findByStatusOrderByMinPointsAsc(String status);
}
