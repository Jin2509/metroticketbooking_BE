package com.metroticket_api.green;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class GreenJourneyCalculationTest {

    private final GreenJourneySettings defaults = new GreenJourneySettings();

    @Test
    void savedCo2AndPointsFromDistance() {
        // Bến Thành → Thủ Đức 13,422 km: (90 − 30) × 13,422 = 805 g; 10 điểm/km → 134 điểm
        var c = GreenJourneyService.calculate(defaults, false, new BigDecimal("13.422"), Duration.ofMinutes(25), 0);
        assertThat(c.counted()).isTrue();
        assertThat(c.co2Grams()).isEqualTo(805);
        assertThat(c.points()).isEqualTo(134);
    }

    @Test
    void antiGamingRules() {
        assertThat(GreenJourneyService.calculate(defaults, true, BigDecimal.ZERO, Duration.ofMinutes(10), 0).reason())
                .contains("cùng ga");
        assertThat(GreenJourneyService.calculate(defaults, false, new BigDecimal("1.0"), Duration.ofSeconds(60), 0).counted())
                .as("quá ngắn").isFalse();
        assertThat(GreenJourneyService.calculate(defaults, false, new BigDecimal("1.0"), Duration.ofHours(5), 0).counted())
                .as("quá dài").isFalse();
        var capped = GreenJourneyService.calculate(defaults, false, new BigDecimal("5.0"), Duration.ofMinutes(10), 6);
        assertThat(capped.counted()).isFalse();
        assertThat(capped.points()).isZero();
        assertThat(capped.reason()).contains("tối đa 6");
    }

    @Test
    void levelsAndNameMasking() {
        assertThat(GreenLevel.of(0)).isEqualTo(GreenLevel.SEED);
        assertThat(GreenLevel.of(5_000)).isEqualTo(GreenLevel.SPROUT);
        assertThat(GreenLevel.of(600_000)).isEqualTo(GreenLevel.FOREST);
        assertThat(GreenLevel.FOREST.next()).isNull();
        assertThat(GreenJourneyService.maskName("Nguyễn Văn An")).isEqualTo("Nguyễn V. A.");
        assertThat(GreenJourneyService.maskName(null)).isEqualTo("Hành khách");
    }
}
