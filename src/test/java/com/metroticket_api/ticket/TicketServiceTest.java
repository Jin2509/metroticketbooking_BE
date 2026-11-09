package com.metroticket_api.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.metroticket_api.metro.station.Station;

class TicketServiceTest {

    private static TicketProduct product(DurationUnit unit, int value, int price, int maxPeriods) {
        return new TicketProduct("P", "P", null, unit, value, price, maxPeriods, 30, ProductStatus.ACTIVE, null);
    }

    private static Ticket ticket(TicketProduct p, int periods, Instant bought) {
        return new Ticket(UUID.randomUUID(), p, periods, bought, bought.plusSeconds(30L * 86400));
    }

    @Test
    void dayAndWeekCountFull24HourDaysFromFirstTap() {
        Instant tap = Instant.parse("2026-11-07T01:00:00Z"); // 08:00 giờ VN
        assertThat(DurationUnit.DAY.expiry(tap, 1)).isEqualTo(Instant.parse("2026-11-08T01:00:00Z"));
        assertThat(DurationUnit.DAY.expiry(tap, 3)).isEqualTo(Instant.parse("2026-11-10T01:00:00Z"));
        assertThat(DurationUnit.WEEK.expiry(tap, 1)).isEqualTo(Instant.parse("2026-11-14T01:00:00Z"));
    }

    @Test
    void monthFollowsCalendarInVietnamTime() {
        // 31/01 08:00 giờ VN → 28/02 08:00 (2027 không nhuận)
        assertThat(DurationUnit.MONTH.expiry(Instant.parse("2027-01-31T01:00:00Z"), 1))
                .isEqualTo(Instant.parse("2027-02-28T01:00:00Z"));
        // 3 tháng
        assertThat(DurationUnit.MONTH.expiry(Instant.parse("2026-11-07T01:00:00Z"), 3))
                .isEqualTo(Instant.parse("2027-02-07T01:00:00Z"));
    }

    @Test
    void multiMonthPurchaseMultipliesPriceAndDuration() {
        TicketProduct monthly = product(DurationUnit.MONTH, 1, 300_000, 12);
        Instant bought = Instant.parse("2026-11-01T00:00:00Z");
        Ticket t = ticket(monthly, 3, bought);
        assertThat(t.getPriceVnd()).isEqualTo(900_000);
        assertThat(t.getUnitPriceVnd()).isEqualTo(300_000);

        Instant tap = Instant.parse("2026-11-07T01:00:00Z");
        t.enter(mock(Station.class), tap);
        assertThat(t.getValidUntil()).isEqualTo(Instant.parse("2027-02-07T01:00:00Z"));
    }

    @Test
    void snapshotIsNotAffectedByLaterProductChanges() {
        TicketProduct day = product(DurationUnit.DAY, 1, 40_000, 1);
        Ticket t = ticket(day, 1, Instant.parse("2026-11-01T00:00:00Z"));
        day.setPriceVnd(50_000);
        day.setDuration(DurationUnit.DAY, 2);
        Instant tap = Instant.parse("2026-11-07T01:00:00Z");
        t.enter(mock(Station.class), tap);
        assertThat(t.getPriceVnd()).isEqualTo(40_000);
        assertThat(t.getValidUntil()).isEqualTo(tap.plusSeconds(86400));
    }

    @Test
    void passReturnsToActiveBetweenTripsAndExpiresIfOverdueOnExit() {
        TicketProduct day = product(DurationUnit.DAY, 1, 40_000, 1);
        Instant bought = Instant.parse("2026-11-01T00:00:00Z");
        Ticket t = ticket(day, 1, bought);

        Instant tap = Instant.parse("2026-11-05T01:00:00Z");
        t.enter(mock(Station.class), tap);
        assertThat(t.getActivatedAt()).isEqualTo(tap);
        t.exit(tap.plusSeconds(1800));
        assertThat(t.getStatus()).isEqualTo(TicketStatus.ACTIVE);

        t.enter(mock(Station.class), tap.plusSeconds(23 * 3600));
        assertThat(t.getValidUntil()).as("lần quẹt sau không gia hạn").isEqualTo(tap.plusSeconds(86400));
        t.exit(tap.plusSeconds(25 * 3600));
        assertThat(t.getStatus()).isEqualTo(TicketStatus.EXPIRED);
        assertThat(t.getTripsCount()).isEqualTo(2);
    }
}
