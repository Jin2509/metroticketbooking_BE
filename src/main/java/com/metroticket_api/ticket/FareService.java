package com.metroticket_api.ticket;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.audit.AuditLogService;
import com.metroticket_api.metro.map.MapService;
import com.metroticket_api.metro.map.RoutePlanner;
import com.metroticket_api.metro.map.RoutePlanner.Optimize;
import com.metroticket_api.ticket.FareController.BandRequest;

/**
 * Giá vé lượt: lấy km của lộ trình ngắn nhất giữa hai ga (tính được cả khi chuyển tuyến) rồi tra bảng fare_band.
 * Bảng giá phải liên tục từ 0 km, không hở, khoảng cuối không giới hạn và giá không giảm khi đi xa.
 */
@Service
public class FareService {

    public record Quote(Long fromStationId, Long toStationId, BigDecimal distanceKm, int priceVnd) {
    }

    private final FareBandRepository bands;
    private final MapService map;
    private final AuditLogService audit;

    public FareService(FareBandRepository bands, MapService map, AuditLogService audit) {
        this.bands = bands;
        this.map = map;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<FareBand> list() {
        return bands.findAllByOrderByMinDistanceKmAsc();
    }

    /** Không có lộ trình thì trả rỗng. */
    @Transactional(readOnly = true)
    public Optional<Quote> quote(Long fromStationId, Long toStationId) {
        return new RoutePlanner(map.loadNetwork()).plan(fromStationId, toStationId, Optimize.SHORTEST)
                .map(RoutePlanner.Journey::totalDistanceKm)
                .map(km -> new Quote(fromStationId, toStationId, km, priceFor(bands.findAllByOrderByMinDistanceKmAsc(), km)));
    }

    /** Thay toàn bộ bảng giá. Vé đã bán giữ nguyên giá. */
    @Transactional
    public List<FareBand> replace(List<BandRequest> requested) {
        validate(requested);
        String old = describe(bands.findAllByOrderByMinDistanceKmAsc());
        bands.deleteAllInBatch();
        List<FareBand> saved = new ArrayList<>();
        for (BandRequest r : requested) {
            saved.add(bands.save(new FareBand(r.minDistanceKm(), r.maxDistanceKm(), r.priceVnd())));
        }
        audit.log("FARE_BAND_REPLACE", "fare_band", null, "Thay bảng giá vé lượt", old, describe(saved));
        return saved;
    }

    static int priceFor(List<FareBand> sorted, BigDecimal km) {
        return sorted.stream().filter(b -> b.contains(km)).findFirst().map(FareBand::getPriceVnd)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Bảng giá vé lượt chưa đầy đủ"));
    }

    static void validate(List<BandRequest> sorted) {
        BigDecimal expectedMin = BigDecimal.ZERO;
        int lastPrice = 0;
        for (int i = 0; i < sorted.size(); i++) {
            BandRequest b = sorted.get(i);
            boolean last = i == sorted.size() - 1;
            if (expectedMin == null || b.minDistanceKm().compareTo(expectedMin) != 0) {
                throw invalid("Các bậc giá phải liên tục từ 0 km, không hở, không chồng lấn");
            }
            if (last != (b.maxDistanceKm() == null)) {
                throw invalid("Chỉ bậc cuối được để trống km tối đa, và bậc cuối bắt buộc để trống");
            }
            if (b.maxDistanceKm() != null && b.maxDistanceKm().compareTo(b.minDistanceKm()) <= 0) {
                throw invalid("Km tối đa phải lớn hơn km tối thiểu");
            }
            if (b.priceVnd() < lastPrice) {
                throw invalid("Giá không được giảm khi đi xa hơn");
            }
            expectedMin = b.maxDistanceKm();
            lastPrice = b.priceVnd();
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String describe(List<FareBand> list) {
        StringBuilder sb = new StringBuilder();
        list.forEach(b -> sb.append(b.getMinDistanceKm()).append('-').append(b.getMaxDistanceKm()).append(':').append(b.getPriceVnd()).append(';'));
        return sb.toString();
    }
}
