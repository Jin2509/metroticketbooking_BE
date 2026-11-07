package com.metroticket_api.parking;

public enum VehicleType {
    BICYCLE,
    MOTORBIKE,
    CAR;

    /** Xe đạp không có biển số; xe máy/ô tô bắt buộc. */
    public boolean requiresPlate() {
        return this != BICYCLE;
    }
}
