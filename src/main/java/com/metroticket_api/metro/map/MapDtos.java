package com.metroticket_api.metro.map;

import java.math.BigDecimal;
import java.util.List;

import com.metroticket_api.metro.common.MetroLineStatus;
import com.metroticket_api.metro.common.StationStatus;
import com.metroticket_api.metro.common.TransferStatus;

public class MapDtos {

    /** Một ga trên một tuyến, kèm luỹ kế khoảng cách/thời gian từ ga đầu tuyến. */
    public record MapLineStop(Long stationId, String stationCode, String stationName, int order,
            double latitude, double longitude, StationStatus status, BigDecimal distanceFromPrevKm,
            int travelTimeFromPrevSeconds, int dwellTimeSeconds, BigDecimal cumulativeDistanceKm,
            long cumulativeTimeSeconds) {
    }

    public record MapLine(Long id, String code, String name, String colorHex, MetroLineStatus status,
            int stationCount, BigDecimal totalDistanceKm, long endToEndTimeSeconds, List<MapLineStop> stops) {
    }

    public record MapStation(Long id, String code, String name, double latitude, double longitude,
            StationStatus status, boolean interchange, String district, List<String> lineCodes) {
    }

    public record MapTransfer(Long stationId, String stationCode, Long fromLineId, String fromLineCode,
            Long toLineId, String toLineCode, int transferTimeMinutes, TransferStatus status) {
    }

    public record NetworkResponse(List<MapLine> lines, List<MapStation> stations, List<MapTransfer> transfers) {
    }
}
