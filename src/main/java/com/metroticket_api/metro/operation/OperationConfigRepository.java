package com.metroticket_api.metro.operation;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationConfigRepository extends JpaRepository<OperationConfig, Long> {

    List<OperationConfig> findByLineIdOrderByEffectiveFromDesc(Long lineId);

    Optional<OperationConfig> findTopByLineIdOrderByVersionDesc(Long lineId);
}
