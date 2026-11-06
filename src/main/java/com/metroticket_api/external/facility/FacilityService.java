package com.metroticket_api.external.facility;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.metroticket_api.metro.station.Station;
import com.metroticket_api.metro.station.StationService;

/**
 * Service phân loại và lọc các tiện ích quanh ga theo category.
 */
@Service
public class FacilityService {

    private final FacilityProvider facilityProvider;
    private final StationService stationService;

    public FacilityService(FacilityProvider facilityProvider, StationService stationService) {
        this.facilityProvider = facilityProvider;
        this.stationService = stationService;
    }

    @Cacheable(value = "facility-nearby",
            key = "'station:' + #stationId + ':category:' + (#category != null ? #category.name() : 'ALL') + ':radius:' + #radiusMeters")
    public List<FacilityDto> getNearbyFacilities(Long stationId, FacilityCategory category, int radiusMeters) {
        Station station = stationService.getById(stationId);
        List<OsmFeature> features = facilityProvider.getNearbyFacilities(station.getLatitude(), station.getLongitude(), radiusMeters);

        List<FacilityDto> result = new ArrayList<>();
        long index = 1L;
        for (OsmFeature feature : features) {
            FacilityCategory mapped = mapCategory(feature);
            if (category != null && mapped != category) {
                continue;
            }

            result.add(new FacilityDto(
                    index++,
                    feature.name(),
                    mapped,
                    feature.latitude(),
                    feature.longitude(),
                    calculateDistance(station.getLatitude(), station.getLongitude(), feature.latitude(), feature.longitude()),
                    null,
                    "OSM"));
        }
        return result;
    }

    private FacilityCategory mapCategory(OsmFeature feature) {
        String amenity = feature.amenity() == null ? "" : feature.amenity().toLowerCase();
        String shop = feature.shop() == null ? "" : feature.shop().toLowerCase();
        String leisure = feature.leisure() == null ? "" : feature.leisure().toLowerCase();
        String tourism = feature.tourism() == null ? "" : feature.tourism().toLowerCase();

        if (amenity.matches("restaurant|cafe|food_court|fast_food|bar|pub|canteen|ice_cream")) {
            return FacilityCategory.FOOD;
        }
        if (amenity.matches("pharmacy|hospital|clinic|dentist|veterinary|doctors")) {
            return FacilityCategory.HEALTH;
        }
        if (amenity.matches("school|college|university|kindergarten|library")) {
            return FacilityCategory.EDUCATION;
        }
        if (amenity.matches("bank|atm|bureau_de_change")) {
            return FacilityCategory.BANK;
        }
        if (amenity.matches("bus_station|train_station|subway_station|subway_entrance|taxi|parking|fuel|bicycle_rental|car_rental|charging_station")) {
            return FacilityCategory.TRANSPORT;
        }

        if (shop.matches("supermarket|convenience|bakery|mall|department_store|clothes|marketplace|greengrocer")) {
            return FacilityCategory.SHOP;
        }
        if (leisure.matches("park|garden|playground|pitch|stadium|sports_centre")) {
            return FacilityCategory.PARK;
        }
        if (tourism.matches("museum|hotel|attraction|gallery|viewpoint")) {
            return FacilityCategory.ENTERTAINMENT;
        }
        if (shop.matches("marketplace")) {
            return FacilityCategory.SHOP;
        }
        return FacilityCategory.OTHER;
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
