package com.metroticket_api.metro.line;

import java.time.Instant;

import com.metroticket_api.metro.common.MetroLineStatus;

public class MetroLineDtos {

    public record CreateLineRequest(
            String code,
            String nameLine,
            String colorHex,
            String description,
            MetroLineStatus status) {
    }

    public record UpdateLineRequest(
            String code,
            String nameLine,
            String colorHex,
            String description,
            MetroLineStatus status) {
    }

    public record LineResponse(
            Long id,
            String code,
            String nameLine,
            String colorHex,
            String description,
            MetroLineStatus status,
            Instant createdAt,
            Instant updatedAt) {
        public static LineResponse of(MetroLine line) {
            return new LineResponse(
                    line.getId(),
                    line.getCode(),
                    line.getNameLine(),
                    line.getColorHex(),
                    line.getDescription(),
                    line.getStatus(),
                    line.getCreatedAt(),
                    line.getUpdatedAt());
        }
    }
}
