package com.metroticket_api.metro.common;

import java.util.EnumSet;
import java.util.Set;

/**
 * Trạng thái đoàn tàu và các bước chuyển hợp lệ.
 *
 * <pre>
 * READY ⇄ IN_OPERATION
 * READY / IN_OPERATION → MAINTENANCE → READY
 * mọi trạng thái → OUT_OF_SERVICE → MAINTENANCE (phải kiểm tra kỹ thuật trước khi chạy lại)
 * </pre>
 */
public enum TrainStatus {
    READY,
    IN_OPERATION,
    MAINTENANCE,
    OUT_OF_SERVICE;

    /** Tàu có thể được điều động chạy theo cấu hình vận hành. */
    public boolean isAvailableForService() {
        return this == READY || this == IN_OPERATION;
    }

    public Set<TrainStatus> allowedNext() {
        return switch (this) {
            case READY -> EnumSet.of(IN_OPERATION, MAINTENANCE, OUT_OF_SERVICE);
            case IN_OPERATION -> EnumSet.of(READY, MAINTENANCE, OUT_OF_SERVICE);
            case MAINTENANCE -> EnumSet.of(READY, OUT_OF_SERVICE);
            case OUT_OF_SERVICE -> EnumSet.of(MAINTENANCE);
        };
    }

    public boolean canTransitionTo(TrainStatus target) {
        return target != null && allowedNext().contains(target);
    }
}
