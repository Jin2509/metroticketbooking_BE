package com.metroticket_api.external.facility;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.metroticket_api.metro.station.Station;
import com.metroticket_api.metro.station.StationService;

/**
 * Service phân loại và giới hạn tiện ích quanh ga theo category.
 */
@Service
public class FacilityService {

    private static final int MAX_RESULTS = 10;
    private static final int MAX_ALL_CATEGORY_RESULTS = 2;

    private final FacilityProvider facilityProvider;
    private final StationService stationService;

    public FacilityService(FacilityProvider facilityProvider, StationService stationService) {
        this.facilityProvider = facilityProvider;
        this.stationService = stationService;
    }

    @Cacheable(value = "facility-nearby",
            key = "'v3:station:' + #stationId + ':category:' + (#category != null ? #category.name() : 'ALL') + ':radius:' + #radiusMeters")
    public List<FacilityDto> getNearbyFacilities(Long stationId, FacilityCategory category, int radiusMeters) {
        Station station = stationService.getById(stationId);
        EnumMap<FacilityCategory, List<FacilityDto>> byCategory = new EnumMap<>(FacilityCategory.class);
        if (category == null) {
            for (FacilityCategory requestedCategory : FacilityCategory.values()) {
                addFacilities(byCategory, requestedCategory, station.getLatitude(), station.getLongitude(),
                        radiusMeters, MAX_ALL_CATEGORY_RESULTS);
            }
        } else {
            addFacilities(byCategory, category, station.getLatitude(), station.getLongitude(),
                    radiusMeters, MAX_RESULTS);
        }

        List<FacilityDto> selected = category == null
                ? selectRoundRobin(byCategory)
                : byCategory.getOrDefault(category, List.of()).stream().limit(MAX_RESULTS).toList();
        return reindex(selected);
    }

    private void addFacilities(EnumMap<FacilityCategory, List<FacilityDto>> byCategory,
            FacilityCategory requestedCategory, BigDecimal latitude, BigDecimal longitude,
            int radiusMeters, int limit) {
        List<FacilityFeature> features = facilityProvider.getNearbyFacilities(
                latitude, longitude, radiusMeters, requestedCategory, limit);
        for (FacilityFeature feature : features) {
            FacilityCategory mapped = mapCategory(feature.categories());
            if (mapped != requestedCategory) {
                continue;
            }
            List<FacilityDto> categoryResults = byCategory.computeIfAbsent(mapped, ignored -> new ArrayList<>());
            categoryResults.add(new FacilityDto(
                    (long) categoryResults.size() + 1,
                    feature.name(),
                    mapped,
                    feature.latitude(),
                    feature.longitude(),
                    calculateDistance(latitude, longitude, feature.latitude(), feature.longitude()),
                    feature.address(),
                    "GEOAPIFY"));
        }
    }

    private List<FacilityDto> reindex(List<FacilityDto> selected) {
        List<FacilityDto> result = new ArrayList<>(selected.size());
        for (FacilityDto facility : selected) {
            result.add(new FacilityDto(
                    (long) result.size() + 1,
                    facility.name(),
                    facility.category(),
                    facility.latitude(),
                    facility.longitude(),
                    facility.distanceMeters(),
                    facility.address(),
                    facility.source()));
        }
        return result;
    }

    private List<FacilityDto> selectRoundRobin(EnumMap<FacilityCategory, List<FacilityDto>> byCategory) {
        List<FacilityDto> result = new ArrayList<>(MAX_RESULTS);
        int round = 0;
        while (result.size() < MAX_RESULTS) {
            boolean addedInRound = false;
            for (FacilityCategory category : FacilityCategory.values()) {
                List<FacilityDto> categoryResults = byCategory.getOrDefault(category, List.of());
                if (round < categoryResults.size()) {
                    result.add(categoryResults.get(round));
                    addedInRound = true;
                    if (result.size() == MAX_RESULTS) {
                        break;
                    }
                }
            }
            if (!addedInRound) {
                break;
            }
            round++;
        }
        return result;
    }

    private FacilityCategory mapCategory(List<String> categories) {
        if (hasCategory(categories, "catering")) {
            return FacilityCategory.FOOD;
        }
        if (hasCategory(categories, "healthcare")) {
            return FacilityCategory.HEALTH;
        }
        if (hasCategory(categories, "education")) {
            return FacilityCategory.EDUCATION;
        }
        if (hasCategory(categories, "office.financial")) {
            return FacilityCategory.BANK;
        }
        if (hasCategory(categories, "public_transport", "parking", "rental", "service.vehicle")) {
            return FacilityCategory.TRANSPORT;
        }
        if (hasCategory(categories, "commercial")) {
            return FacilityCategory.SHOP;
        }
        if (hasCategory(categories, "leisure")) {
            return FacilityCategory.PARK;
        }
        if (hasCategory(categories, "entertainment", "tourism", "accommodation")) {
            return FacilityCategory.ENTERTAINMENT;
        }
        return FacilityCategory.OTHER;
    }

    private boolean hasCategory(List<String> categories, String... prefixes) {
        return categories != null && categories.stream()
                .anyMatch(categoryValue -> Arrays.stream(prefixes)
                        .anyMatch(prefix -> categoryValue.equals(prefix) || categoryValue.startsWith(prefix + ".")));
    }

    private double calculateDistance(BigDecimal lat1, BigDecimal lon1, BigDecimal lat2, BigDecimal lon2) {
        double earthRadius = 6371000.0;
        double dLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLon = Math.toRadians(lon2.doubleValue() - lon1.doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1.doubleValue())) * Math.cos(Math.toRadians(lat2.doubleValue()))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadius * c;
    }
}
