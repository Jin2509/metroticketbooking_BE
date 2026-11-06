package com.metroticket_api.metro.station;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.metroticket_api.metro.common.StationStatus;

public class StationDtos {

    public record CreateStationRequest(
            @NotBlank @Size(max = 20) String code,
            @NotBlank @Size(max = 100) String nameStation,
            @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
            @Size(max = 255) String address,
            @Size(max = 100) String district,
            @Size(max = 500) String imageUrl,
            Boolean isInterchange,
            StationStatus status) {
    }

    public record UpdateStationRequest(
            @Size(max = 20) String code,
            @Size(max = 100) String nameStation,
            @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
            @Size(max = 255) String address,
            @Size(max = 100) String district,
            @Size(max = 500) String imageUrl,
            Boolean isInterchange,
            StationStatus status) {
    }

    public record StationOrderItem(Long stationId, Integer stationOrder) {
    }

    public record AddLineStationRequest(
            @NotNull Long stationId,
            @Positive Integer stationOrder,
            @PositiveOrZero @DecimalMax("99999.999") BigDecimal distanceFromPrevKm,
            @PositiveOrZero Integer travelTimeFromPrevSeconds,
            @PositiveOrZero Integer dwellTimeSeconds) {
    }

    public record UpdateLineStationSegmentRequest(
            @PositiveOrZero @DecimalMax("99999.999") BigDecimal distanceFromPrevKm,
            @PositiveOrZero Integer travelTimeFromPrevSeconds,
            @PositiveOrZero Integer dwellTimeSeconds) {
    }

    public record UpdateStationOrderRequest(List<StationOrderItem> stations) {
    }

    public record LineStationResponse(
            Long id,
            Long lineId,
            String lineCode,
            String lineName,
            StationResponse station,
            Integer stationOrder,
            BigDecimal distanceFromPrevKm,
            Integer travelTimeFromPrevSeconds,
            Integer dwellTimeSeconds,
            Instant createdAt) {
        public static LineStationResponse of(LineStation lineStation) {
            return new LineStationResponse(
                    lineStation.getId(),
                    lineStation.getLine().getId(),
                    lineStation.getLine().getCode(),
                    lineStation.getLine().getNameLine(),
                    StationResponse.of(lineStation.getStation()),
                    lineStation.getStationOrder(),
                    lineStation.getDistanceFromPrevKm(),
                    lineStation.getTravelTimeFromPrevSeconds(),
                    lineStation.getDwellTimeSeconds(),
                    lineStation.getCreatedAt());
        }
    }

    public record StationResponse(
            Long id,
            String code,
            String nameStation,
            BigDecimal latitude,
            BigDecimal longitude,
            String address,
            String district,
            String imageUrl,
            Boolean isInterchange,
            StationStatus status,
            Instant createdAt,
            Instant updatedAt) {
        public static StationResponse of(Station station) {
            return new StationResponse(
                    station.getId(),
                    station.getCode(),
                    station.getNameStation(),
                    station.getLatitude(),
                    station.getLongitude(),
                    station.getAddress(),
                    station.getDistrict(),
                    station.getImageUrl(),
                    station.getIsInterchange(),
                    station.getStatus(),
                    station.getCreatedAt(),
                    station.getUpdatedAt());
        }
    }
}
