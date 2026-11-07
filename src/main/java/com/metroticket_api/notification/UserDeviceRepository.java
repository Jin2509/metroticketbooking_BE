package com.metroticket_api.notification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserDeviceRepository extends JpaRepository<UserDevice, Long> {

    Optional<UserDevice> findByToken(String token);

    List<UserDevice> findByUserIdAndDisabledAtIsNull(UUID userId);

    long countByUserIdAndDisabledAtIsNull(UUID userId);
}
