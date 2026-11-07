package com.metroticket_api.green;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class GreenJourneyDtos {

    public record Totals(long trips, BigDecimal distanceKm, BigDecimal co2SavedKg, long points,
            BigDecimal treeYearsEquivalent) {
    }

    public record LevelInfo(GreenLevel level, String label, GreenLevel nextLevel, String nextLabel,
            BigDecimal co2KgToNextLevel) {
    }

    public record SummaryResponse(boolean enabled, Totals allTime, Totals thisMonth, LevelInfo level,
            Integer monthRank, String message) {
    }

    public record TripResponse(Long id, String entryStationCode, String entryStationName, String exitStationCode,
            String exitStationName, Instant entryAt, Instant exitAt, BigDecimal distanceKm, int co2SavedGrams,
            int points, boolean counted, String notCountedReason) {
        static TripResponse of(GreenTrip t) {
            return new TripResponse(t.getId(), t.getEntryStation().getCode(), t.getEntryStation().getNameStation(),
                    t.getExitStation().getCode(), t.getExitStation().getNameStation(), t.getEntryAt(), t.getExitAt(),
                    t.getDistanceKm(), t.getCo2SavedGrams(), t.getPoints(), t.isCounted(), t.getNotCountedReason());
        }
    }

    /** Bảng xếp hạng: chỉ tên viết tắt (không lộ email / họ tên đầy đủ). */
    public record LeaderboardEntry(int rank, String displayName, long points, BigDecimal co2SavedKg, long trips,
            boolean me) {
    }

    public record LeaderboardResponse(String month, List<LeaderboardEntry> entries) {
    }

    public record PublicStatsResponse(long trips, BigDecimal distanceKm, BigDecimal co2SavedKg,
            BigDecimal treeYearsEquivalent) {
    }

    public record SettingsResponse(boolean enabled, int baselineGramsPerKm, int metroGramsPerKm, int pointsPerKm,
            int minTripMinutes, int maxTripMinutes, int maxCountedTripsPerDay, int treeGramsPerYear, long version,
            Instant updatedAt) {
        static SettingsResponse of(GreenJourneySettings s) {
            return new SettingsResponse(s.isEnabled(), s.getBaselineGramsPerKm(), s.getMetroGramsPerKm(),
                    s.getPointsPerKm(), s.getMinTripMinutes(), s.getMaxTripMinutes(), s.getMaxCountedTripsPerDay(),
                    s.getTreeGramsPerYear(), s.getVersion(), s.getUpdatedAt());
        }
    }

    public record UpdateSettingsRequest(
            @NotNull Long version,
            Boolean enabled,
            @Min(1) @Max(1000) Integer baselineGramsPerKm,
            @Min(0) @Max(1000) Integer metroGramsPerKm,
            @Min(0) @Max(1000) Integer pointsPerKm,
            @Min(0) @Max(60) Integer minTripMinutes,
            @Min(10) @Max(1440) Integer maxTripMinutes,
            @Min(1) @Max(50) Integer maxCountedTripsPerDay,
            @Min(1000) @Max(100000) Integer treeGramsPerYear) {
    }
}
