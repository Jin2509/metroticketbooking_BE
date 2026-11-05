package com.metroticket_api.metro.line;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.metroticket_api.metro.common.MetroLineStatus;

public interface MetroLineRepository extends JpaRepository<MetroLine, Long> {

    Optional<MetroLine> findByCode(String code);

    Page<MetroLine> findAllByStatus(MetroLineStatus status, Pageable pageable);

    Page<MetroLine> findByNameLineContainingIgnoreCase(String nameLine, Pageable pageable);
}
