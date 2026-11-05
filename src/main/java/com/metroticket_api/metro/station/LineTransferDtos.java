package com.metroticket_api.metro.station;

import java.time.Instant;

import com.metroticket_api.metro.common.TransferStatus;

public class LineTransferDtos {

    public record CreateLineTransferRequest(
            Long stationId,
            Long fromLineId,
            Long toLineId,
            Integer transferTimeMinutes,
            TransferStatus status) {
    }

    public record UpdateLineTransferRequest(
            Long stationId,
            Long fromLineId,
            Long toLineId,
            Integer transferTimeMinutes,
            TransferStatus status) {
    }

    public record LineTransferResponse(
            Long id,
            Long stationId,
            Long fromLineId,
            Long toLineId,
            Integer transferTimeMinutes,
            TransferStatus status,
            Instant createdAt,
            Instant updatedAt) {
        public static LineTransferResponse of(LineTransfer transfer) {
            return new LineTransferResponse(
                    transfer.getId(),
                    transfer.getStation() == null ? null : transfer.getStation().getId(),
                    transfer.getFromLine() == null ? null : transfer.getFromLine().getId(),
                    transfer.getToLine() == null ? null : transfer.getToLine().getId(),
                    transfer.getTransferTimeMinutes(),
                    transfer.getStatus(),
                    transfer.getCreatedAt(),
                    transfer.getUpdatedAt());
        }
    }
}
