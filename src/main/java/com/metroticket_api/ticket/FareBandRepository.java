package com.metroticket_api.ticket;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FareBandRepository extends JpaRepository<FareBand, Long> {

    List<FareBand> findAllByOrderByMinDistanceKmAsc();
}
