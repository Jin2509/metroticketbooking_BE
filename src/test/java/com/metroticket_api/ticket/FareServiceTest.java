package com.metroticket_api.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** Công thức giá vé lượt: max(giá tối thiểu, làm tròn LÊN(km × đơn giá/km, bước làm tròn)). */
class FareServiceTest {

    private static int price(String km, int perKm, int rounding, int min) {
        return FareService.price(new BigDecimal(km), perKm, rounding, min);
    }

    @Test
    void roundsUpToTheRoundingStep() {
        // 13,422 km × 1.000 đ = 13.422 đ → 14.000 đ
        assertThat(price("13.422", 1000, 1000, 7000)).isEqualTo(14000);
        // vừa chẵn bậc thì không cộng thêm
        assertThat(price("12.000", 1000, 1000, 7000)).isEqualTo(12000);
        // chỉ lố 1 đồng cũng làm tròn lên
        assertThat(price("12.001", 1000, 1000, 7000)).isEqualTo(13000);
        // bước 500 đ
        assertThat(price("13.422", 1000, 500, 0)).isEqualTo(13500);
        assertThat(price("13.422", 1500, 1000, 0)).isEqualTo(21000); // 20.133 → 21.000
    }

    @Test
    void neverBelowMinimumFare() {
        assertThat(price("0.700", 1000, 1000, 7000)).isEqualTo(7000);
        assertThat(price("0", 1000, 1000, 7000)).isEqualTo(7000);
        assertThat(price("0.700", 1000, 1000, 0)).isEqualTo(1000);
    }

    @Test
    void growsWithDistanceAndRate() {
        assertThat(price("19.700", 1000, 1000, 7000)).isGreaterThan(price("13.422", 1000, 1000, 7000));
        assertThat(price("13.422", 2000, 1000, 7000)).isEqualTo(27000);
    }

    @Test
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> price("1", 0, 1000, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> price("1", 1000, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> price("-1", 1000, 1000, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
