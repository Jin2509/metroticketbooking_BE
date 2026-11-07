package com.metroticket_api.ticket;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TicketProductServiceTest {

    private static TicketProduct p(DurationUnit unit, int value, int maxPeriods) {
        return new TicketProduct("X", "X", null, unit, value, 100_000, maxPeriods, 30, ProductStatus.ACTIVE, null);
    }

    @Test
    void acceptsTheFourStandardPasses() {
        assertThatCode(() -> TicketProductService.validate(p(DurationUnit.DAY, 1, 1))).doesNotThrowAnyException();
        assertThatCode(() -> TicketProductService.validate(p(DurationUnit.DAY, 3, 1))).doesNotThrowAnyException();
        assertThatCode(() -> TicketProductService.validate(p(DurationUnit.WEEK, 1, 1))).doesNotThrowAnyException();
        assertThatCode(() -> TicketProductService.validate(p(DurationUnit.MONTH, 1, 12))).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidDurationsAndMultiPeriodOutsideMonthly() {
        assertThatThrownBy(() -> TicketProductService.validate(p(DurationUnit.DAY, 7, 1)))
                .as("7 ngày phải khai báo là 1 tuần").isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> TicketProductService.validate(p(DurationUnit.WEEK, 4, 1)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> TicketProductService.validate(p(DurationUnit.DAY, 1, 2)))
                .as("chỉ vé tháng mua nhiều kỳ").isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> TicketProductService.validate(p(DurationUnit.MONTH, 3, 6)))
                .as("3 tháng × 6 kỳ = 18 tháng > 12").isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> TicketProductService.validate(p(DurationUnit.MONTH, 1, 13)))
                .isInstanceOf(ResponseStatusException.class);
    }
}
