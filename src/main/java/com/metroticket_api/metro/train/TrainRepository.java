package com.metroticket_api.metro.train;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TrainRepository extends JpaRepository<Train, Long> {

    boolean existsByCode(String code);

    List<Train> findByLineId(Long lineId);
}
