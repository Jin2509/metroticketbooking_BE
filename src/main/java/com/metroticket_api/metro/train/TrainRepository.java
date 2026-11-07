package com.metroticket_api.metro.train;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TrainRepository extends JpaRepository<Train, Long>, JpaSpecificationExecutor<Train> {

    boolean existsByCode(String code);

    Optional<Train> findByCode(String code);

    List<Train> findByLineId(Long lineId);
}
