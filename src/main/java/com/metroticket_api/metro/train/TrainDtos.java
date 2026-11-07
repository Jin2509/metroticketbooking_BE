package com.metroticket_api.metro.train;

import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.metroticket_api.metro.common.TrainStatus;
import com.metroticket_api.metro.line.MetroLine;

public class TrainDtos {

    public record CreateTrainRequest(
            @NotBlank @Size(max = 30) String code,
            @Size(max = 100) String nameTrain,
            Long lineId,
            @Positive @Max(5000) Integer capacity,
            @Min(1901) @Max(2100) Integer manufactureYear,
            TrainStatus status) {
    }

    /** null = giữ nguyên. Đổi trạng thái phải theo {@link TrainStatus#allowedNext()}. */
    public record UpdateTrainRequest(
            @Size(max = 30) String code,
            @Size(max = 100) String nameTrain,
            Long lineId,
            @Positive @Max(5000) Integer capacity,
            @Min(1901) @Max(2100) Integer manufactureYear,
            TrainStatus status) {
    }

    public record TrainResponse(
            Long id,
            String code,
            String nameTrain,
            Long lineId,
            String lineCode,
            String lineName,
            Integer capacity,
            Integer manufactureYear,
            TrainStatus status,
            Instant createdAt,
            Instant updatedAt) {
        public static TrainResponse of(Train train) {
            MetroLine line = train.getLine();
            return new TrainResponse(
                    train.getId(),
                    train.getCode(),
                    train.getNameTrain(),
                    line == null ? null : line.getId(),
                    line == null ? null : line.getCode(),
                    line == null ? null : line.getNameLine(),
                    train.getCapacity(),
                    train.getManufactureYear(),
                    train.getStatus(),
                    train.getCreatedAt(),
                    train.getUpdatedAt());
        }
    }
}
