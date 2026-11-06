package com.metroticket_api.metro.station;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.common.SortGuard;
import com.metroticket_api.metro.common.StationStatus;
import com.metroticket_api.metro.common.TransferStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.line.MetroLineDtos.LineResponse;
import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.station.LineTransferDtos.LineTransferResponse;
import com.metroticket_api.metro.station.StationDtos.CreateStationRequest;
import com.metroticket_api.metro.station.StationDtos.LineStationResponse;
import com.metroticket_api.metro.station.StationDtos.StationOrderItem;
import com.metroticket_api.metro.station.StationDtos.StationResponse;
import com.metroticket_api.metro.station.StationDtos.UpdateStationRequest;

/**
 * Service xử lý nghiệp vụ ga Metro.
 *
 * <p>Class này tập hợp logic tìm kiếm, lọc tuyến, tính khoảng cách gần vị trí và truy vấn các kết nối gắn với mỗi ga.</p>
 */
@Service
public class StationService {

    private static final double EARTH_RADIUS_KM = 6371.0;

    /**
     * Tốc độ trung bình dùng để ước lượng travel_time khi một đoạn được tính lại tự động
     * (khớp với dữ liệu seed tuyến số 1: ~60 giây mỗi km). Admin có thể chỉnh lại qua API /segment.
     */
    private static final int ESTIMATED_SECONDS_PER_KM = 60;

    /** Các field client được phép truyền vào tham số sort của GET /stations. */
    public static final Set<String> SORTABLE_FIELDS = Set.of("id", "code", "nameStation", "district", "status", "createdAt", "updatedAt");

    private final StationRepository stations;
    private final LineStationRepository lineStations;
    private final LineTransferRepository lineTransfers;
    private final MetroLineRepository lines;

    public StationService(StationRepository stations,
            LineStationRepository lineStations,
            LineTransferRepository lineTransfers,
            MetroLineRepository lines) {
        this.stations = stations;
        this.lineStations = lineStations;
        this.lineTransfers = lineTransfers;
        this.lines = lines;
    }

    /**
     * Lấy danh sách ga theo bộ lọc. Khi client truyền lineId, danh sách giữ đúng thứ tự ga trên tuyến
     * và vẫn áp dụng q/status cùng phân trang.
     */
    @Transactional(readOnly = true)
    public Page<StationResponse> list(String q, Long lineId, StationStatus status, Pageable pageable) {
        SortGuard.requireAllowed(pageable, SORTABLE_FIELDS);
        boolean hasQuery = q != null && !q.isBlank();
        if (lineId != null) {
            String needle = hasQuery ? q.trim().toLowerCase() : null;
            List<StationResponse> filtered = lineStations.findByLineIdOrderByStationOrderAsc(lineId).stream()
                    .map(LineStation::getStation)
                    .filter(s -> needle == null || s.getNameStation().toLowerCase().contains(needle))
                    .filter(s -> status == null || s.getStatus() == status)
                    .map(StationResponse::of)
                    .toList();
            return toPage(filtered, pageable);
        }
        if (hasQuery && status != null) {
            return stations.searchByNameAndStatus(q.trim(), status, pageable).map(StationResponse::of);
        }
        if (hasQuery) {
            return stations.searchByName(q.trim(), pageable).map(StationResponse::of);
        }
        if (status != null) {
            return stations.findAllByStatus(status, pageable).map(StationResponse::of);
        }
        return stations.findAll(pageable).map(StationResponse::of);
    }

    /**
     * Lấy toàn bộ ga mà không phân trang cho các màn hình select, map và danh sách đầy đủ.
     */
    @Transactional(readOnly = true)
    public List<StationResponse> listAll() {
        return stations.findAll().stream().map(StationResponse::of).toList();
    }

    /**
     * Lấy chi tiết một nhà ga theo ID. Nếu không tìm thấy, trả về 404 để client biết dữ liệu không tồn tại.
     */
    @Transactional(readOnly = true)
    public Station getById(Long stationId) {
        return stations.findById(stationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy nhà ga"));
    }

    /**
     * Tạo mới nhà ga. Luôn kiểm tra mã ga, tên ga và tọa độ vì đây là dữ liệu căn bản dùng cho bản đồ và điều hướng.
     */
    @Transactional
    public StationResponse create(CreateStationRequest request) {
        if (request.code() == null || request.code().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã ga không được để trống");
        }
        if (request.nameStation() == null || request.nameStation().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên ga không được để trống");
        }
        if (request.latitude() == null || request.longitude() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tọa độ ga không được để trống");
        }
        String code = request.code().trim();
        if (stations.existsByCode(code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã ga đã tồn tại");
        }
        Station station = new Station(
                code,
                request.nameStation().trim(),
                request.latitude(),
                request.longitude(),
                blankToNull(request.address()),
                blankToNull(request.district()),
                blankToNull(request.imageUrl()),
                request.isInterchange(),
                request.status());
        return StationResponse.of(stations.save(station));
    }

    /**
     * Cập nhật thông tin nhà ga. Trường nào client không gửi (null) thì giữ nguyên;
     * gửi chuỗi rỗng cho address/district/imageUrl để xóa giá trị.
     */
    @Transactional
    public StationResponse update(Long stationId, UpdateStationRequest request) {
        Station station = getById(stationId);
        if (request.code() != null && !request.code().isBlank()) {
            String code = request.code().trim();
            if (!code.equals(station.getCode()) && stations.existsByCode(code)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã ga đã tồn tại");
            }
            station.setCode(code);
        }
        if (request.nameStation() != null && !request.nameStation().isBlank()) {
            station.setNameStation(request.nameStation().trim());
        }
        if (request.latitude() != null) {
            station.setLatitude(request.latitude());
        }
        if (request.longitude() != null) {
            station.setLongitude(request.longitude());
        }
        if (request.address() != null) {
            station.setAddress(blankToNull(request.address()));
        }
        if (request.district() != null) {
            station.setDistrict(blankToNull(request.district()));
        }
        if (request.imageUrl() != null) {
            station.setImageUrl(blankToNull(request.imageUrl()));
        }
        if (request.isInterchange() != null) {
            station.setIsInterchange(request.isInterchange());
        }
        if (request.status() != null) {
            station.setStatus(request.status());
        }
        // Đổi tọa độ không tự ghi đè khoảng cách/thời gian đã cấu hình của các đoạn;
        // chỉnh lại qua PUT /lines/{lineId}/stations/{stationId}/segment nếu cần.
        return StationResponse.of(stations.save(station));
    }

    /**
     * Xóa ga khỏi hệ thống. Ga đang thuộc một tuyến bất kỳ thì phải gỡ khỏi tuyến trước.
     */
    @Transactional
    public void delete(Long stationId) {
        if (!stations.existsById(stationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy nhà ga");
        }
        if (lineStations.existsByStationId(stationId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ga đang thuộc ít nhất một tuyến, hãy gỡ ga khỏi tuyến trước khi xóa");
        }
        stations.deleteById(stationId);
    }

    /**
     * Trả về danh sách mối quan hệ ga - tuyến theo thứ tự station_order; dùng để render tuyến đường trên map hoặc màn hình ga.
     */
    @Transactional(readOnly = true)
    public List<LineStation> lineStations(Long stationId) {
        getById(stationId);
        return lineStations.findByStationIdOrderByStationOrderAsc(stationId);
    }

    /**
     * Liệt kê các kết nối chuyển tuyến của ga để người dùng có thể biết ga đó kết nối với tuyến nào.
     */
    @Transactional(readOnly = true)
    public List<LineTransferResponse> connections(Long stationId) {
        getById(stationId);
        return lineTransfers.findByStationId(stationId).stream()
                .map(LineTransferResponse::of)
                .toList();
    }

    /**
     * Lấy các tuyến đi qua một ga để phục vụ xem nhanh danh sách tuyến tại ga.
     */
    @Transactional(readOnly = true)
    public List<LineResponse> linesByStation(Long stationId) {
        getById(stationId);
        return lineStations.findByStationId(stationId).stream()
                .map(LineStation::getLine)
                .map(LineResponse::of)
                .toList();
    }

    /**
     * Tìm các ga trong bán kính {@code maxDistanceKm} (km, theo công thức haversine) quanh vị trí người dùng,
     * sắp xếp từ gần đến xa.
     */
    @Transactional(readOnly = true)
    public List<StationResponse> nearest(BigDecimal lat, BigDecimal lng, double maxDistanceKm) {
        if (lat.compareTo(BigDecimal.valueOf(-90)) < 0 || lat.compareTo(BigDecimal.valueOf(90)) > 0
                || lng.compareTo(BigDecimal.valueOf(-180)) < 0 || lng.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tọa độ không hợp lệ");
        }
        if (!(maxDistanceKm > 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "radiusKm phải lớn hơn 0");
        }
        double fromLat = lat.doubleValue();
        double fromLng = lng.doubleValue();
        record Candidate(Station station, double distanceKm) {
        }
        return stations.findAll().stream()
                .map(s -> new Candidate(s, haversineKm(fromLat, fromLng,
                        s.getLatitude().doubleValue(), s.getLongitude().doubleValue())))
                .filter(c -> c.distanceKm() <= maxDistanceKm)
                .sorted(Comparator.comparingDouble(Candidate::distanceKm))
                .map(c -> StationResponse.of(c.station()))
                .toList();
    }

    /**
     * Cập nhật thứ tự ga trên một tuyến. Danh sách phải chứa đầy đủ mọi ga của tuyến, thứ tự liên tục từ 1..n.
     */
    @Transactional
    public List<LineStationResponse> updateStationOrder(Long lineId, List<StationOrderItem> items) {
        requireLine(lineId);
        if (items == null || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Danh sách thứ tự ga không được rỗng");
        }

        List<LineStation> existing = lineStations.findByLineIdOrderByStationOrderAsc(lineId);
        if (existing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tuyến chưa có ga nào, hãy thêm ga trước khi sắp xếp thứ tự");
        }

        Map<Long, LineStation> byStationId = existing.stream()
                .collect(Collectors.toMap(ls -> ls.getStation().getId(), ls -> ls));
        Set<Long> incomingIds = new HashSet<>();
        Set<Integer> orderSet = new HashSet<>();

        for (StationOrderItem item : items) {
            if (item == null || item.stationId() == null || item.stationOrder() == null || item.stationOrder() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mỗi ga phải có stationId và stationOrder > 0");
            }
            if (!byStationId.containsKey(item.stationId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ga " + item.stationId() + " không thuộc tuyến " + lineId);
            }
            if (!incomingIds.add(item.stationId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ga " + item.stationId() + " xuất hiện nhiều lần trong danh sách");
            }
            if (!orderSet.add(item.stationOrder())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thứ tự ga không được trùng nhau");
            }
        }

        if (!incomingIds.equals(byStationId.keySet())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Danh sách cập nhật phải bao gồm đầy đủ toàn bộ ga của tuyến hiện tại");
        }

        // Chuẩn hóa về 1..n theo thứ tự client gửi để tránh khoảng trống trong station_order.
        List<StationOrderItem> sorted = items.stream()
                .sorted(Comparator.comparing(StationOrderItem::stationOrder))
                .toList();
        Map<LineStation, Integer> targets = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            targets.put(byStationId.get(sorted.get(i).stationId()), i + 1);
        }

        Map<Long, Long> previousBefore = previousStationMap(existing);
        applyOrders(existing, targets);
        recalculateChangedSegments(lineId, previousBefore, Set.of());

        return lineStations.findByLineIdOrderByStationOrderAsc(lineId).stream()
                .map(LineStationResponse::of)
                .toList();
    }

    /**
     * Thêm một ga vào tuyến tại vị trí được chỉ định. Nếu không truyền stationOrder thì mặc định đặt cuối tuyến.
     * Thông số đoạn không được truyền sẽ được ước lượng từ tọa độ; đoạn của ga ngay sau vị trí chèn cũng được tính lại.
     */
    @Transactional
    public LineStationResponse addStationToLine(Long lineId, Long stationId, Integer stationOrder,
            BigDecimal distanceFromPrevKm, Integer travelTimeFromPrevSeconds, Integer dwellTimeSeconds) {
        MetroLine line = requireLine(lineId);
        Station station = getById(stationId);
        if (stationOrder != null && stationOrder <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stationOrder phải lớn hơn 0");
        }
        validateSegmentValues(distanceFromPrevKm, travelTimeFromPrevSeconds, dwellTimeSeconds);

        if (lineStations.existsByLineIdAndStationId(lineId, stationId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ga đã tồn tại trong tuyến");
        }

        List<LineStation> ordered = lineStations.findByLineIdOrderByStationOrderAsc(lineId);
        Map<Long, Long> previousBefore = previousStationMap(ordered);

        // Chuẩn hóa thứ tự hiện có về 1..n, chừa chỗ cho ga mới tại vị trí insertAt.
        int insertAt = stationOrder == null ? ordered.size() + 1 : Math.min(stationOrder, ordered.size() + 1);
        Map<LineStation, Integer> targets = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            int position = i + 1;
            targets.put(ordered.get(i), position >= insertAt ? position + 1 : position);
        }
        applyOrders(ordered, targets);

        LineStation lineStation = new LineStation(
                line,
                station,
                insertAt,
                distanceFromPrevKm,
                travelTimeFromPrevSeconds,
                dwellTimeSeconds);
        lineStations.saveAndFlush(lineStation);

        Set<Long> explicit = distanceFromPrevKm != null || travelTimeFromPrevSeconds != null
                ? Set.of(stationId)
                : Set.of();
        recalculateChangedSegments(lineId, previousBefore, explicit);

        return lineStations.findByLineIdOrderByStationOrderAsc(lineId).stream()
                .filter(ls -> ls.getStation().getId().equals(stationId))
                .findFirst()
                .map(LineStationResponse::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ga vừa thêm không tìm thấy trong tuyến"));
    }

    /**
     * Cập nhật các thông số của đoạn đường từ ga trước đó đến ga này trong tuyến.
     */
    @Transactional
    public LineStationResponse updateStationSegment(Long lineId, Long stationId, BigDecimal distanceFromPrevKm,
            Integer travelTimeFromPrevSeconds, Integer dwellTimeSeconds) {
        requireLine(lineId);
        validateSegmentValues(distanceFromPrevKm, travelTimeFromPrevSeconds, dwellTimeSeconds);
        LineStation lineStation = lineStations.findByLineIdOrderByStationOrderAsc(lineId).stream()
                .filter(ls -> ls.getStation().getId().equals(stationId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ga không thuộc tuyến"));

        if (distanceFromPrevKm != null) {
            lineStation.setDistanceFromPrevKm(distanceFromPrevKm);
        }
        if (travelTimeFromPrevSeconds != null) {
            lineStation.setTravelTimeFromPrevSeconds(travelTimeFromPrevSeconds);
        }
        if (dwellTimeSeconds != null) {
            lineStation.setDwellTimeSeconds(dwellTimeSeconds);
        }
        return LineStationResponse.of(lineStations.save(lineStation));
    }

    /**
     * Xóa một ga khỏi tuyến. Hành động này chỉ làm mất quan hệ tuyến-ga, không xóa bản ghi station.
     * Ga liền sau sẽ nối thẳng với ga liền trước nên đoạn của nó được tính lại.
     */
    @Transactional
    public void removeStationFromLine(Long lineId, Long stationId) {
        requireLine(lineId);
        if (!stations.existsById(stationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ga");
        }

        List<LineStation> ordered = lineStations.findByLineIdOrderByStationOrderAsc(lineId);
        LineStation removed = ordered.stream()
                .filter(ls -> ls.getStation().getId().equals(stationId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ga không thuộc tuyến"));
        Map<Long, Long> previousBefore = previousStationMap(ordered);

        // Phải xóa và flush trước, nếu không Hibernate chạy UPDATE trước DELETE và đụng UNIQUE(line_id, station_order).
        lineStations.delete(removed);
        lineStations.flush();

        List<LineStation> remaining = ordered.stream().filter(ls -> ls != removed).toList();
        Map<LineStation, Integer> targets = new HashMap<>();
        for (int i = 0; i < remaining.size(); i++) {
            targets.put(remaining.get(i), i + 1);
        }
        applyOrders(remaining, targets);
        recalculateChangedSegments(lineId, previousBefore, Set.of());
    }

    /**
     * Gán station_order mới qua 2 pha để không vi phạm ràng buộc UNIQUE(line_id, station_order)
     * (Postgres kiểm tra ngay từng câu UPDATE và Hibernate không đảm bảo thứ tự các câu UPDATE):
     * pha 1 đẩy các dòng cần đổi sang dải giá trị tạm lớn hơn mọi giá trị hiện có, pha 2 gán giá trị cuối.
     */
    private void applyOrders(List<LineStation> rows, Map<LineStation, Integer> targets) {
        List<LineStation> changing = rows.stream()
                .filter(ls -> !Objects.equals(ls.getStationOrder(), targets.get(ls)))
                .toList();
        if (changing.isEmpty()) {
            return;
        }
        int maxCurrent = rows.stream().mapToInt(LineStation::getStationOrder).max().orElse(0);
        int maxTarget = targets.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int tempBase = Math.max(maxCurrent, maxTarget) + 1;

        for (int i = 0; i < changing.size(); i++) {
            changing.get(i).setStationOrder(tempBase + i);
        }
        lineStations.saveAllAndFlush(changing);

        for (LineStation ls : changing) {
            ls.setStationOrder(targets.get(ls));
        }
        lineStations.saveAllAndFlush(changing);
    }

    /** stationId -> stationId của ga liền trước (null nếu là ga đầu). */
    private static Map<Long, Long> previousStationMap(List<LineStation> ordered) {
        Map<Long, Long> result = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            Long previous = i == 0 ? null : ordered.get(i - 1).getStation().getId();
            result.put(ordered.get(i).getStation().getId(), previous);
        }
        return result;
    }

    /**
     * Chỉ tính lại các đoạn mà ga liền trước đã thay đổi (hoặc ga mới thêm không có số liệu),
     * giữ nguyên khoảng cách/thời gian đã cấu hình cho các đoạn còn lại.
     *
     * @param explicitStationIds ga mới thêm mà client đã tự truyền thông số đoạn
     */
    private void recalculateChangedSegments(Long lineId, Map<Long, Long> previousBefore, Set<Long> explicitStationIds) {
        List<LineStation> ordered = lineStations.findByLineIdOrderByStationOrderAsc(lineId);
        List<LineStation> dirty = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            LineStation current = ordered.get(i);
            Long stationId = current.getStation().getId();
            if (i == 0) {
                if (!isZero(current.getDistanceFromPrevKm()) || !Objects.equals(current.getTravelTimeFromPrevSeconds(), 0)) {
                    current.setDistanceFromPrevKm(BigDecimal.ZERO.setScale(3));
                    current.setTravelTimeFromPrevSeconds(0);
                    dirty.add(current);
                }
                continue;
            }
            LineStation previous = ordered.get(i - 1);
            boolean isNew = !previousBefore.containsKey(stationId);
            boolean previousChanged = !isNew && !Objects.equals(previousBefore.get(stationId), previous.getStation().getId());

            if (isNew && explicitStationIds.contains(stationId)) {
                // Client đã truyền số liệu: chỉ bổ sung phần còn thiếu.
                if (current.getDistanceFromPrevKm() == null) {
                    current.setDistanceFromPrevKm(calculateBirdDistanceKm(previous.getStation(), current.getStation()));
                }
                if (current.getTravelTimeFromPrevSeconds() == null) {
                    current.setTravelTimeFromPrevSeconds(estimateTravelSeconds(current.getDistanceFromPrevKm()));
                }
                dirty.add(current);
            } else if (isNew || previousChanged) {
                BigDecimal distance = calculateBirdDistanceKm(previous.getStation(), current.getStation());
                current.setDistanceFromPrevKm(distance);
                current.setTravelTimeFromPrevSeconds(estimateTravelSeconds(distance));
                dirty.add(current);
            }
        }
        if (!dirty.isEmpty()) {
            lineStations.saveAll(dirty);
        }
    }

    private static boolean isZero(BigDecimal value) {
        return value != null && value.signum() == 0;
    }

    private static int estimateTravelSeconds(BigDecimal distanceKm) {
        if (distanceKm == null) {
            return 1;
        }
        int seconds = distanceKm.multiply(BigDecimal.valueOf(ESTIMATED_SECONDS_PER_KM))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
        // Mô phỏng tàu yêu cầu travel_time > 0 cho mọi đoạn trừ ga đầu.
        return Math.max(1, seconds);
    }

    private static void validateSegmentValues(BigDecimal distanceFromPrevKm, Integer travelTimeFromPrevSeconds,
            Integer dwellTimeSeconds) {
        if (distanceFromPrevKm != null && distanceFromPrevKm.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "distanceFromPrevKm không được âm");
        }
        if (travelTimeFromPrevSeconds != null && travelTimeFromPrevSeconds < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "travelTimeFromPrevSeconds không được âm");
        }
        if (dwellTimeSeconds != null && dwellTimeSeconds < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dwellTimeSeconds không được âm");
        }
    }

    private BigDecimal calculateBirdDistanceKm(Station from, Station to) {
        if (from == null || to == null
                || from.getLatitude() == null || from.getLongitude() == null
                || to.getLatitude() == null || to.getLongitude() == null) {
            return BigDecimal.ZERO.setScale(3);
        }
        double distanceKm = haversineKm(
                from.getLatitude().doubleValue(), from.getLongitude().doubleValue(),
                to.getLatitude().doubleValue(), to.getLongitude().doubleValue());
        return BigDecimal.valueOf(distanceKm).setScale(3, RoundingMode.HALF_UP);
    }

    static double haversineKm(double lat1Deg, double lng1Deg, double lat2Deg, double lng2Deg) {
        double lat1 = Math.toRadians(lat1Deg);
        double lat2 = Math.toRadians(lat2Deg);
        double deltaLat = Math.toRadians(lat2Deg - lat1Deg);
        double deltaLng = Math.toRadians(lng2Deg - lng1Deg);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private MetroLine requireLine(Long lineId) {
        return lines.findById(lineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tuyến"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static <T> Page<T> toPage(List<T> all, Pageable pageable) {
        if (pageable.isUnpaged()) {
            return new PageImpl<>(all, pageable, all.size());
        }
        int from = (int) Math.min(pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return new PageImpl<>(all.subList(from, to), pageable, all.size());
    }

    /**
     * Tạo mới một mối nối chuyển tuyến giữa 2 tuyến tại một nhà ga.
     */
    @Transactional
    public LineTransfer createLineTransfer(Long stationId, Long fromLineId, Long toLineId, Integer transferTimeMinutes, TransferStatus status) {
        if (stationId == null || fromLineId == null || toLineId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stationId, fromLineId và toLineId là bắt buộc");
        }
        Station station = getById(stationId);
        MetroLine fromLine = lines.findById(fromLineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tuyến xuất phát"));
        MetroLine toLine = lines.findById(toLineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tuyến đích"));
        validateTransfer(null, stationId, fromLineId, toLineId, transferTimeMinutes);

        LineTransfer transfer = new LineTransfer(station, fromLine, toLine, transferTimeMinutes, status);
        markInterchange(station);
        return lineTransfers.save(transfer);
    }

    /**
     * Cập nhật thông tin chuyển tuyến theo ID.
     */
    @Transactional
    public LineTransfer updateLineTransfer(Long transferId, Long stationId, Long fromLineId, Long toLineId,
            Integer transferTimeMinutes, TransferStatus status) {
        LineTransfer transfer = lineTransfers.findById(transferId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thông tin chuyển tuyến"));
        if (stationId != null) {
            transfer.setStation(getById(stationId));
        }
        if (fromLineId != null) {
            transfer.setFromLine(lines.findById(fromLineId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tuyến xuất phát")));
        }
        if (toLineId != null) {
            transfer.setToLine(lines.findById(toLineId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tuyến đích")));
        }
        validateTransfer(transferId,
                transfer.getStation().getId(),
                transfer.getFromLine().getId(),
                transfer.getToLine().getId(),
                transferTimeMinutes);
        if (transferTimeMinutes != null) {
            transfer.setTransferTimeMinutes(transferTimeMinutes);
        }
        if (status != null) {
            transfer.setStatus(status);
        }
        markInterchange(transfer.getStation());
        return lineTransfers.save(transfer);
    }

    /**
     * Kiểm tra một điểm chuyển tuyến hợp lệ: 2 tuyến khác nhau, ga phải nằm trên cả 2 tuyến và không trùng bản ghi khác.
     */
    private void validateTransfer(Long currentId, Long stationId, Long fromLineId, Long toLineId, Integer transferTimeMinutes) {
        if (fromLineId.equals(toLineId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tuyến xuất phát và tuyến đích phải khác nhau");
        }
        if (transferTimeMinutes != null && transferTimeMinutes < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "transferTimeMinutes không được âm");
        }
        if (!lineStations.existsByLineIdAndStationId(fromLineId, stationId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ga không thuộc tuyến xuất phát");
        }
        if (!lineStations.existsByLineIdAndStationId(toLineId, stationId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ga không thuộc tuyến đích");
        }
        lineTransfers.findByStationIdAndFromLineIdAndToLineId(stationId, fromLineId, toLineId)
                .filter(existing -> !existing.getId().equals(currentId))
                .ifPresent(existing -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Điểm chuyển tuyến này đã tồn tại");
                });
    }

    private void markInterchange(Station station) {
        if (!Boolean.TRUE.equals(station.getIsInterchange())) {
            station.setIsInterchange(true);
            stations.save(station);
        }
    }

    /**
     * Xóa thông tin chuyển tuyến theo ID.
     */
    @Transactional
    public void deleteLineTransfer(Long transferId) {
        if (!lineTransfers.existsById(transferId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thông tin chuyển tuyến");
        }
        lineTransfers.deleteById(transferId);
    }

    @Transactional(readOnly = true)
    public List<LineTransfer> allLineTransfers() {
        return lineTransfers.findAll();
    }

    @Transactional(readOnly = true)
    public LineTransfer getLineTransfer(Long transferId) {
        return lineTransfers.findById(transferId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thông tin chuyển tuyến"));
    }
}
