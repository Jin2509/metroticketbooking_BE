package com.metroticket_api.metro.station;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.metroticket_api.metro.common.StationStatus;

public class StationDtos {

    public record CreateStationRequest(
            String code,
            String nameStation,
            BigDecimal latitude,
            BigDecimal longitude,
            String address,
            String district,
            String imageUrl,
            Boolean isInterchange,
            StationStatus status) {
    }

    public record UpdateStationRequest(
            String code,
            String nameStation,
            BigDecimal latitude,
            BigDecimal longitude,
            String address,
            String district,
            String imageUrl,
            Boolean isInterchange,
            StationStatus status) {
    }

    public record StationOrderItem(Long stationId, Integer stationOrder) {
    }

    public record AddLineStationRequest(
            Long stationId,
            Integer stationOrder,
            BigDecimal distanceFromPrevKm,
            Integer travelTimeFromPrevSeconds,
            Integer dwellTimeSeconds) {
    }

    public record UpdateLineStationSegmentRequest(
            BigDecimal distanceFromPrevKm,
            Integer travelTimeFromPrevSeconds,
            Integer dwellTimeSeconds) {
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
