package com.metroticket_api.external.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.metroticket_api.metro.station.Station;
import com.metroticket_api.metro.station.StationService;

class FacilityServiceTest {

    @Test
    void unfilteredFacilitiesAreSelectedRoundRobinAcrossCategories() {
        FacilityProvider provider = mock(FacilityProvider.class);
        StationService stations = mock(StationService.class);
        Station station = mock(Station.class);
        when(stations.getById(1L)).thenReturn(station);
        when(station.getLatitude()).thenReturn(new BigDecimal("10.77"));
        when(station.getLongitude()).thenReturn(new BigDecimal("106.69"));

        when(provider.getNearbyFacilities(any(), any(), eq(500), any(FacilityCategory.class), anyInt()))
                .thenAnswer(invocation -> {
                    FacilityCategory category = invocation.getArgument(3);
                    return List.of(
                            feature(category + " 1", geoapifyCategory(category)),
                            feature(category + " 2", geoapifyCategory(category)));
                });

        List<FacilityDto> facilities = new FacilityService(provider, stations)
                .getNearbyFacilities(1L, null, 500);

        assertThat(facilities).hasSize(10);
        assertThat(facilities).extracting(FacilityDto::category)
                .startsWith(
                        FacilityCategory.FOOD,
                        FacilityCategory.HEALTH,
                        FacilityCategory.EDUCATION,
                        FacilityCategory.SHOP)
                .contains(
                        FacilityCategory.PARK,
                        FacilityCategory.BANK,
                        FacilityCategory.TRANSPORT,
                        FacilityCategory.ENTERTAINMENT);
        assertThat(facilities).extracting(FacilityDto::id)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        verify(provider, times(FacilityCategory.values().length))
                .getNearbyFacilities(any(), any(), eq(500), any(FacilityCategory.class), eq(2));
    }

    private String geoapifyCategory(FacilityCategory category) {
        return switch (category) {
            case FOOD -> "catering.cafe";
            case HEALTH -> "healthcare.clinic";
            case EDUCATION -> "education.school";
            case SHOP -> "commercial.convenience";
            case PARK -> "leisure.park";
            case BANK -> "office.financial";
            case TRANSPORT -> "public_transport.platform";
            case ENTERTAINMENT -> "entertainment.cinema";
            case OTHER -> "natural.park";
        };
    }

    private FacilityFeature feature(String name, String category) {
        return new FacilityFeature(
                name,
                new BigDecimal("10.77"),
                new BigDecimal("106.69"),
                List.of(category),
                null);
    }
}
