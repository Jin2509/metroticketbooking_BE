package com.metroticket_api.metro.station;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LineStationRepository extends JpaRepository<LineStation, Long> {

    @Query("select ls from LineStation ls join fetch ls.station s join fetch ls.line l where l.id = :lineId order by ls.stationOrder asc")
    List<LineStation> findByLineIdOrderByStationOrderAsc(@Param("lineId") Long lineId);

    @Query("select ls from LineStation ls join fetch ls.line l join fetch ls.station s where s.id = :stationId order by ls.stationOrder asc")
    List<LineStation> findByStationIdOrderByStationOrderAsc(@Param("stationId") Long stationId);

    @Query("select ls from LineStation ls join fetch ls.line l where ls.station.id = :stationId")
    List<LineStation> findByStationId(@Param("stationId") Long stationId);

    boolean existsByLineIdAndStationId(Long lineId, Long stationId);

    boolean existsByStationId(Long stationId);

    void deleteByLineIdAndStationId(Long lineId, Long stationId);
}
