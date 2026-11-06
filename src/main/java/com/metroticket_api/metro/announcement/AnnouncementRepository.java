package com.metroticket_api.metro.announcement;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.metroticket_api.metro.common.AnnouncementStatus;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    List<Announcement> findByLineIdOrderByStartsAtDesc(Long lineId);

    List<Announcement> findByStationIdOrderByStartsAtDesc(Long stationId);

    List<Announcement> findAllByStatusOrderByStartsAtDesc(AnnouncementStatus status);
}
