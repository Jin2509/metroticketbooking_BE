package com.metroticket_api.system;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface SystemParamRepository extends JpaRepository<SystemParam, Long> {

    @Query("""
            select p from SystemParam p
            where p.paramKey = :key and p.effectiveFrom <= :at and (p.effectiveTo is null or p.effectiveTo > :at)
            """)
    Optional<SystemParam> findEffective(String key, Instant at);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SystemParam p where p.paramKey = :key and p.effectiveTo is null")
    Optional<SystemParam> findLatestForUpdate(String key);

    List<SystemParam> findByEffectiveToIsNullOrderByParamGroupAscParamKeyAsc();

    List<SystemParam> findByParamKeyOrderByEffectiveFromDesc(String key);
}
