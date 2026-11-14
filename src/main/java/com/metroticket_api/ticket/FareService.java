package com.metroticket_api.ticket;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.map.MapService;
import com.metroticket_api.metro.map.RoutePlanner;
import com.metroticket_api.metro.map.RoutePlanner.Optimize;
import com.metroticket_api.system.SystemParam;
import com.metroticket_api.system.SystemParamKey;
import com.metroticket_api.system.SystemParamService;

/**
 * Giá vé lượt theo quãng đường. Lấy km của lộ trình ngắn nhất giữa hai ga (tính được cả khi chuyển tuyến), rồi:
 *
 * <pre>giá = max(FARE_MIN_VND, làm tròn LÊN(km × FARE_PER_KM_&lt;loại vé&gt;, tới bội số FARE_ROUNDING_VND))</pre>
 *
 * Đơn giá/km riêng cho từng loại vé lượt, bước làm tròn và giá tối thiểu đều là tham số hệ thống có lịch sử hiệu lực,
 * nên đổi giá không ảnh hưởng vé đã bán (vé lưu snapshot giá và đơn giá/km lúc mua).
 */
@Service
public class FareService {

    /** Mã loại vé lượt mặc định khi không chỉ định (chatbot, báo giá nhanh). */
    public static final String DEFAULT_SINGLE_CODE = "SINGLE";

    public record Quote(String productCode, Long fromStationId, Long toStationId, BigDecimal distanceKm,
            int farePerKmVnd, int roundingVnd, int minFareVnd, int priceVnd) {
    }

    /** Cấu hình giá của một loại vé lượt đang bán. */
    public record FareConfig(String productCode, String productName, int farePerKmVnd, int roundingVnd,
            int minFareVnd) {
    }

    private final MapService map;
    private final SystemParamService params;
    private final TicketProductRepository products;

    public FareService(MapService map, SystemParamService params, TicketProductRepository products) {
        this.map = map;
        this.params = params;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public List<FareConfig> list() {
        int rounding = params.intValue(SystemParamKey.FARE_ROUNDING_VND);
        int min = params.intValue(SystemParamKey.FARE_MIN_VND);
        return products.findByStatusOrderByIdAsc(ProductStatus.ACTIVE).stream()
                .filter(TicketProduct::isSingleJourney)
                .map(p -> new FareConfig(p.getCode(), p.getName(), params.farePerKm(p.getCode()), rounding, min))
                .toList();
    }

    /** Báo giá theo loại vé lượt mặc định. */
    @Transactional(readOnly = true)
    public Optional<Quote> quote(Long fromStationId, Long toStationId) {
        return quote(defaultSingleProduct().getCode(), fromStationId, toStationId);
    }

    /** Không có lộ trình thì trả rỗng. */
    @Transactional(readOnly = true)
    public Optional<Quote> quote(String productCode, Long fromStationId, Long toStationId) {
        TicketProduct product = requireSingleProduct(productCode);
        int perKm = params.farePerKm(product.getCode());
        return distanceKm(fromStationId, toStationId).map(km -> quoteFor(product.getCode(), fromStationId, toStationId, km, perKm));
    }

    /** Km của lộ trình ngắn nhất, rỗng nếu không có đường đi. */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> distanceKm(Long fromStationId, Long toStationId) {
        return new RoutePlanner(map.loadNetwork()).plan(fromStationId, toStationId, Optimize.SHORTEST)
                .map(RoutePlanner.Journey::totalDistanceKm);
    }

    /** Giá cho quãng đường với đơn giá/km cho trước, bước làm tròn và giá tối thiểu đang hiệu lực. */
    @Transactional(readOnly = true)
    public int priceFor(BigDecimal km, int farePerKmVnd) {
        return price(km, farePerKmVnd, params.intValue(SystemParamKey.FARE_ROUNDING_VND),
                params.intValue(SystemParamKey.FARE_MIN_VND));
    }

    private Quote quoteFor(String code, Long from, Long to, BigDecimal km, int perKm) {
        int rounding = params.intValue(SystemParamKey.FARE_ROUNDING_VND);
        int min = params.intValue(SystemParamKey.FARE_MIN_VND);
        return new Quote(code, from, to, km, perKm, rounding, min, price(km, perKm, rounding, min));
    }

    /** km × đơn giá, làm tròn LÊN tới bội số {@code roundingVnd}, không thấp hơn {@code minFareVnd}. */
    static int price(BigDecimal km, int farePerKmVnd, int roundingVnd, int minFareVnd) {
        if (km.signum() < 0 || farePerKmVnd <= 0 || roundingVnd <= 0) {
            throw new IllegalArgumentException("Tham số giá vé lượt không hợp lệ");
        }
        BigDecimal step = BigDecimal.valueOf(roundingVnd);
        BigDecimal raw = km.multiply(BigDecimal.valueOf(farePerKmVnd));
        int rounded = raw.divide(step, 0, RoundingMode.CEILING).multiply(step).intValueExact();
        return Math.max(rounded, minFareVnd);
    }

    TicketProduct requireSingleProduct(String productCode) {
        String code = productCode == null || productCode.isBlank() ? null : productCode.trim().toUpperCase(Locale.ROOT);
        if (code == null) {
            return defaultSingleProduct();
        }
        TicketProduct product = products.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy loại vé"));
        if (!product.isSingleJourney()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loại vé " + code + " không phải vé lượt");
        }
        return product;
    }

    /** SINGLE nếu đang bán, ngược lại loại vé lượt đang bán đầu tiên. */
    private TicketProduct defaultSingleProduct() {
        return products.findByCode(DEFAULT_SINGLE_CODE)
                .filter(p -> p.isSingleJourney() && p.getStatus() == ProductStatus.ACTIVE)
                .or(() -> products.findByStatusOrderByIdAsc(ProductStatus.ACTIVE).stream()
                        .filter(TicketProduct::isSingleJourney).findFirst())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hiện không bán vé lượt"));
    }

    /** Đổi đơn giá/km của một loại vé lượt (null = áp dụng ngay). */
    @Transactional
    public SystemParam updateFarePerKm(String productCode, int farePerKmVnd, Instant effectiveFrom) {
        TicketProduct product = requireSingleProduct(productCode);
        return params.update(SystemParamKey.farePerKmKey(product.getCode()), farePerKmVnd, effectiveFrom);
    }
}
