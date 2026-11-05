package com.metroticket_api.metro.station;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.common.StationStatus;
import com.metroticket_api.metro.common.TransferStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.station.StationDtos.CreateStationRequest;
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
     * Lấy danh sách ga theo bộ lọc. Khi client truyền lineId, ta lấy tập station thuộc tuyến đó
     * rồi build lại Page để giữ API tương thích với paging của Spring mà không cần thêm custom repository.
     */
    @Transactional(readOnly = true)
    public Page<StationResponse> list(String q, Long lineId, StationStatus status, Pageable pageable) {
        if (lineId != null) {
            List<Long> stationIds = lineStations.findByLineIdOrderByStationOrderAsc(lineId).stream()
                    .map(ls -> ls.getStation().getId())
                    .toList();
            if (stationIds.isEmpty()) {
                return Page.empty(pageable);
            }
            List<Station> filtered = new ArrayList<>();
            for (Station station : stations.findAllById(stationIds)) {
                if (stationIds.contains(station.getId())) {
                    filtered.add(station);
                }
            }
            return new PageImpl<>(filtered.stream().map(StationResponse::of).toList(), pageable, filtered.size());
        }
        if (q != null && !q.isBlank() && status != null) {
            return stations.searchByNameAndStatus(q, status, pageable).map(StationResponse::of);
        }
        if (q != null && !q.isBlank()) {
            return stations.searchByName(q, pageable).map(StationResponse::of);
        }
        if (status != null) {
            return stations.findAllByStatus(status, pageable).map(StationResponse::of);
        }
        return stations.findAll(pageable).map(StationResponse::of);
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
        if (stations.existsByCode(request.code())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã ga đã tồn tại");
        }
        Station station = new Station(
                request.code(),
                request.nameStation(),
                request.latitude(),
                request.longitude(),
                request.address(),
                request.district(),
                request.imageUrl(),
                request.isInterchange(),
                request.status());
        return StationResponse.of(stations.save(station));
    }

    /**
     * Cập nhật thông tin nhà ga. Chỉ cập nhật các trường được client truyền vào để tránh ghi đè dữ liệu không mong muốn.
     */
    @Transactional
    public StationResponse update(Long stationId, UpdateStationRequest request) {
        Station station = getById(stationId);
        if (request.code() != null && !request.code().isBlank()) {
            if (stations.existsByCode(request.code()) && !request.code().equals(station.getCode())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã ga đã tồn tại");
            }
            station.setCode(request.code());
        }
        if (request.nameStation() != null && !request.nameStation().isBlank()) {
            station.setNameStation(request.nameStation());
        }
        if (request.latitude() != null) {
            station.setLatitude(request.latitude());
        }
        if (request.longitude() != null) {
            station.setLongitude(request.longitude());
        }
        station.setAddress(request.address());
        station.setDistrict(request.district());
        station.setImageUrl(request.imageUrl());
        if (request.isInterchange() != null) {
            station.setIsInterchange(request.isInterchange());
        }
        if (request.status() != null) {
            station.setStatus(request.status());
        }
        return StationResponse.of(stations.save(station));
    }

    /**
     * Xóa ga khỏi hệ thống sau khi kiểm tra sự tồn tại để tránh thao tác trên dữ liệu không hợp lệ.
     */
    @Transactional
    public void delete(Long stationId) {
        if (!stations.existsById(stationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy nhà ga");
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
    public List<LineTransfer> connections(Long stationId) {
        getById(stationId);
        return lineTransfers.findByStationId(stationId);
    }

    /**
     * Lấy các tuyến đi qua một ga để phục vụ xem nhanh danh sách tuyến tại ga.
     */
    @Transactional(readOnly = true)
    public List<MetroLine> linesByStation(Long stationId) {
        getById(stationId);
        return lineStations.findByStationId(stationId).stream()
                .map(LineStation::getLine)
                .toList();
    }

    /**
     * Tìm ga gần nhất trong phạm vi bán kính cho mô-đun "nearest station".
     * Với dữ liệu quy mô vừa phải, phép lọc trong bộ nhớ là đủ và cho phép rút gọn logic query.
     */
    @Transactional(readOnly = true)
    public List<Station> nearest(BigDecimal lat, BigDecimal lng, double maxDistanceKm) {
        return stations.findAll().stream()
                .filter(station -> {
                    BigDecimal latDelta = station.getLatitude().subtract(lat).abs();
                    BigDecimal lngDelta = station.getLongitude().subtract(lng).abs();
                    double distance = Math.sqrt(Math.pow(latDelta.doubleValue(), 2) + Math.pow(lngDelta.doubleValue(), 2));
                    return distance <= maxDistanceKm;
                })
                .toList();
    }

    /**
     * Cập nhật thứ tự ga trên một tuyến. Method này đảm bảo mọi ga thuộc tuyến đều nhận một vị trí lọt thẻ,
     * tránh trường hợp trùng số thứ tự hoặc thiếu station trên tuyến.
     */
    @Transactional
    public List<LineStation> updateStationOrder(Long lineId, List<StationOrderItem> items) {
        MetroLine line = lines.findById(lineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tuyến"));
        if (items == null || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Danh sách thứ tự ga không được rỗng");
        }

        List<LineStation> existing = lineStations.findByLineIdOrderByStationOrderAsc(lineId);
        if (existing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tuyến chưa có ga nào, hãy thêm ga trước khi sắp xếp thứ tự");
        }

        Set<Long> existingIds = existing.stream().map(ls -> ls.getStation().getId()).collect(java.util.stream.Collectors.toSet());
        Set<Long> incomingIds = new HashSet<>();
        Set<Integer> orderSet = new HashSet<>();

        for (StationOrderItem item : items) {
            if (item == null || item.stationId() == null || item.stationOrder() == null || item.stationOrder() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mỗi ga phải có stationId và stationOrder > 0");
            }
            if (!existingIds.contains(item.stationId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ga " + item.stationId() + " không thuộc tuyến " + lineId);
            }
            if (!incomingIds.add(item.stationId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ga " + item.stationId() + " xuất hiện nhiều lần trong danh sách");
            }
            if (!orderSet.add(item.stationOrder())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thứ tự ga không được trùng nhau");
            }
        }

        if (!incomingIds.equals(existingIds)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Danh sách cập nhật phải bao gồm đầy đủ toàn bộ ga của tuyến hiện tại");
        }

        List<LineStation> result = new ArrayList<>();
        for (StationOrderItem item : items) {
            LineStation lineStation = existing.stream()
                    .filter(ls -> ls.getStation().getId().equals(item.stationId()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Ga " + item.stationId() + " không thuộc tuyến " + lineId));
            lineStation.setStationOrder(item.stationOrder());
            result.add(lineStations.save(lineStation));
        }
        return result;
    }

    /**
     * Thêm một ga vào tuyến tại vị trí cuối cùng. Nếu tuyến chưa có ga nào thì gán station_order = 1.
     */
    @Transactional
    public LineStation addStationToLine(Long lineId, Long stationId, BigDecimal distanceFromPrevKm,
            Integer travelTimeFromPrevSeconds, Integer dwellTimeSeconds) {
        MetroLine line = lines.findById(lineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tuyến"));
        Station station = getById(stationId);

        if (lineStations.existsByLineIdAndStationId(lineId, stationId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ga đã tồn tại trong tuyến");
        }

        Integer nextOrder = lineStations.findByLineIdOrderByStationOrderAsc(lineId).stream()
                .map(LineStation::getStationOrder)
                .max(Integer::compareTo)
                .map(order -> order + 1)
                .orElse(1);

        LineStation lineStation = new LineStation(
                line,
                station,
                nextOrder,
                distanceFromPrevKm,
                travelTimeFromPrevSeconds,
                dwellTimeSeconds);
        return lineStations.save(lineStation);
    }

    /**
     * Xóa một ga khỏi tuyến. Hành động này chỉ làm mất quan hệ tuyến-ga, không xóa bản ghi station.
     */
    @Transactional
    public void removeStationFromLine(Long lineId, Long stationId) {
        if (!lines.existsById(lineId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tuyến");
        }
        if (!stations.existsById(stationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ga");
        }
        if (!lineStations.existsByLineIdAndStationId(lineId, stationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ga không thuộc tuyến");
        }
        lineStations.deleteByLineIdAndStationId(lineId, stationId);
    }

    /**
     * Tạo mới một mối nối chuyển tuyến giữa 2 tuyến tại một nhà ga.
     */
    @Transactional
    public LineTransfer createLineTransfer(Long stationId, Long fromLineId, Long toLineId, Integer transferTimeMinutes, TransferStatus status) {
        Station station = getById(stationId);
        MetroLine fromLine = lines.findById(fromLineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tuyến xuất phát"));
        MetroLine toLine = lines.findById(toLineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tuyến đích"));
        if (fromLineId.equals(toLineId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tuyến xuất phát và tuyến đích phải khác nhau");
        }
        LineTransfer transfer = new LineTransfer(station, fromLine, toLine, transferTimeMinutes, status);
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
        if (transferTimeMinutes != null) {
            transfer.setTransferTimeMinutes(transferTimeMinutes);
        }
        if (status != null) {
            transfer.setStatus(status);
        }
        return lineTransfers.save(transfer);
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
