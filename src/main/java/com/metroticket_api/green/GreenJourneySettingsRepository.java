package com.metroticket_api.green;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GreenJourneySettingsRepository extends JpaRepository<GreenJourneySettings, Short> {

    default GreenJourneySettings current() {
        return findById((short) 1).orElseGet(GreenJourneySettings::new);
    }
}
