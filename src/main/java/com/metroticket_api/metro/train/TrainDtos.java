package com.metroticket_api.metro.train;

import java.time.Instant;

import com.metroticket_api.metro.common.TrainStatus;
import com.metroticket_api.metro.line.MetroLine;

public class TrainDtos {

    public record CreateTrainRequest(
            String code,
            String nameTrain,
            Long lineId,
            Integer capacity,
            Integer manufactureYear,
            TrainStatus status) {
    }

    public record UpdateTrainRequest(
            String code,
            String nameTrain,
            Long lineId,
            Integer capacity,
            Integer manufactureYear,
            TrainStatus status) {
    }

    public record TrainResponse(
            Long id,
            String code,
            String nameTrain,
            Long lineId,
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
                    line == null ? null : line.getNameLine(),
                    train.getCapacity(),
                    train.getManufactureYear(),
                    train.getStatus(),
                    train.getCreatedAt(),
                    train.getUpdatedAt());
        }
    }
}
