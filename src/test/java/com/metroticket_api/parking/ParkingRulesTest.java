package com.metroticket_api.parking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ParkingRulesTest {

    @Test
    void sameDayIsOneDayTurnAndEachMidnightAddsOvernight() {
        // 07:00 → 21:00 giờ VN cùng ngày
        Instant in = Instant.parse("2026-11-07T00:00:00Z");
        assertThat(ParkingFeeCalculator.fee(5000, 10000, in, Instant.parse("2026-11-07T14:00:00Z"))).isEqualTo(5000);
        // qua 1 đêm: lấy xe 07:00 hôm sau
        assertThat(ParkingFeeCalculator.fee(5000, 10000, in, Instant.parse("2026-11-08T00:00:00Z"))).isEqualTo(15000);
        // 23:30 VN → 00:30 VN: vẫn tính qua đêm
        assertThat(ParkingFeeCalculator.nightsCrossed(Instant.parse("2026-11-07T16:30:00Z"),
                Instant.parse("2026-11-07T17:30:00Z"))).isEqualTo(1);
        // qua 3 đêm
        assertThat(ParkingFeeCalculator.fee(5000, 10000, in, Instant.parse("2026-11-10T03:00:00Z"))).isEqualTo(35000);
    }

    @Test
    void discountRoundsDown() {
        assertThat(ParkingFeeCalculator.discount(5000, 50)).isEqualTo(2500);
        assertThat(ParkingFeeCalculator.discount(15001, 50)).isEqualTo(7500);
        assertThat(ParkingFeeCalculator.discount(2000, 100)).isEqualTo(2000);
        assertThat(ParkingFeeCalculator.discount(2000, 0)).isZero();
    }

    @Test
    void openingHoursSupportOvernightWindow() {
        LocalTime five = LocalTime.of(5, 0);
        LocalTime eleven = LocalTime.of(23, 0);
        assertThat(ParkingFeeCalculator.isOpen(five, eleven, Instant.parse("2026-11-07T00:00:00Z"))).isTrue(); // 07:00
        assertThat(ParkingFeeCalculator.isOpen(five, eleven, Instant.parse("2026-11-07T16:30:00Z"))).isFalse(); // 23:30
        LocalTime one = LocalTime.of(1, 0);
        assertThat(ParkingFeeCalculator.isOpen(five, one, Instant.parse("2026-11-07T17:30:00Z"))).isTrue(); // 00:30
        assertThat(ParkingFeeCalculator.isOpen(five, one, Instant.parse("2026-11-07T19:00:00Z"))).isFalse(); // 02:00
    }

    @Test
    void plateNumbersAreNormalizedAndValidated() {
        assertThat(PlateNumbers.require("59-X1 123.45", VehicleType.MOTORBIKE)).isEqualTo("59X112345");
        assertThat(PlateNumbers.require("51a-123.45", VehicleType.CAR)).isEqualTo("51A12345");
        assertThat(PlateNumbers.require(null, VehicleType.BICYCLE)).isNull();
        assertThatThrownBy(() -> PlateNumbers.require(null, VehicleType.MOTORBIKE)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> PlateNumbers.require("ABC", VehicleType.CAR)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void monthlyPassCoversParkingOnlyUntilItExpires() {
        ParkingLot lot = mock(ParkingLot.class);
        ParkingRate rate = new ParkingRate(lot, VehicleType.MOTORBIKE);
        rate.update(100, 5000, 10000, 120000, 50);
        Instant passFrom = Instant.parse("2026-10-10T00:00:00Z");
        Instant passUntil = Instant.parse("2026-11-09T00:00:00Z");
        ParkingMonthlyPass pass = new ParkingMonthlyPass(UUID.randomUUID(), lot, VehicleType.MOTORBIKE, "59X112345",
                120000, passFrom, passUntil);
        ParkingSession session = new ParkingSession(lot, VehicleType.MOTORBIKE, "59X112345", pass, null,
                Instant.parse("2026-11-07T00:00:00Z"));

        assertThat(ParkingService.estimate(session, rate, Instant.parse("2026-11-08T12:00:00Z"))).isZero();
        // vé hết hạn 07:00 VN ngày 09/11, lấy xe 07:00 VN ngày 10/11: tính 1 lượt ngày + 1 đêm cho phần vượt
        assertThat(ParkingService.estimate(session, rate, Instant.parse("2026-11-10T00:00:00Z"))).isEqualTo(15000);

        ParkingSession walkIn = new ParkingSession(lot, VehicleType.MOTORBIKE, "59X199999", null, null,
                Instant.parse("2026-11-07T00:00:00Z"));
        assertThat(ParkingService.estimate(walkIn, rate, Instant.parse("2026-11-07T10:00:00Z"))).isEqualTo(5000);
    }
}
