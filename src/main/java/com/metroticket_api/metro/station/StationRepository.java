package com.metroticket_api.metro.station;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.metroticket_api.metro.common.StationStatus;

public interface StationRepository extends JpaRepository<Station, Long> {

    boolean existsByCode(String code);

    List<Station> findByStatus(StationStatus status);

    Page<Station> findAllByStatus(StationStatus status, Pageable pageable);

    @Query("select s from Station s where lower(s.nameStation) like lower(concat('%', :q, '%'))")
    Page<Station> searchByName(String q, Pageable pageable);

    @Query("select s from Station s where lower(s.nameStation) like lower(concat('%', :q, '%')) and s.status = :status")
    Page<Station> searchByNameAndStatus(String q, StationStatus status, Pageable pageable);
}
