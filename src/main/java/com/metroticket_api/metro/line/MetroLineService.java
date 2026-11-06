package com.metroticket_api.metro.line;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.common.MetroLineStatus;
import com.metroticket_api.metro.common.SortGuard;
import com.metroticket_api.metro.line.MetroLineDtos.CreateLineRequest;
import com.metroticket_api.metro.line.MetroLineDtos.LineResponse;
import com.metroticket_api.metro.line.MetroLineDtos.UpdateLineRequest;
import com.metroticket_api.metro.station.LineStation;
import com.metroticket_api.metro.station.LineStationRepository;
import com.metroticket_api.metro.station.LineTransfer;
import com.metroticket_api.metro.station.LineTransferRepository;
import com.metroticket_api.metro.station.LineTransferDtos.LineTransferResponse;
import com.metroticket_api.metro.station.StationDtos.LineStationResponse;

/**
 * Service chứa toàn bộ logic nghiệp vụ cho việc quản lý tuyến Metro.
 *
 * <p>Các phương thức ở đây kiểm tra dữ liệu đầu vào, lấy dữ liệu từ repository, và chuẩn hóa response
 * để controller chỉ tập trung vào HTTP layer.</p>
 */
@Service
public class MetroLineService {

    private final MetroLineRepository lines;
    private final LineStationRepository lineStationRepository;
    private final LineTransferRepository lineTransferRepository;

    /** Các field client được phép truyền vào tham số sort của GET /lines. */
    public static final Set<String> SORTABLE_FIELDS = Set.of("id", "code", "nameLine", "status", "createdAt", "updatedAt");

    public MetroLineService(MetroLineRepository lines,
            LineStationRepository lineStationRepository,
            LineTransferRepository lineTransferRepository) {
        this.lines = lines;
        this.lineStationRepository = lineStationRepository;
        this.lineTransferRepository = lineTransferRepository;
    }

    /**
     * Lấy danh sách tuyến cho màn hình quản lý hoặc danh mục tuyến trên ứng dụng.
     * Các bộ lọc code (khớp chính xác, không phân biệt hoa thường), name (chứa chuỗi) và status được kết hợp với nhau (AND).
     */
    @Transactional(readOnly = true)
    public Page<LineResponse> list(String code, String name, MetroLineStatus status, Pageable pageable) {
        SortGuard.requireAllowed(pageable, SORTABLE_FIELDS);
        Specification<MetroLine> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (code != null && !code.isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("code")), code.trim().toLowerCase()));
            }
            if (name != null && !name.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("nameLine")), "%" + name.trim().toLowerCase() + "%"));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return lines.findAll(spec, pageable).map(LineResponse::of);
    }

    /**
     * Lấy toàn bộ tuyến mà không phân trang để frontend dễ bind dropdown/select.
     */
    @Transactional(readOnly = true)
    public List<LineResponse> listAll() {
        return lines.findAll().stream().map(LineResponse::of).toList();
    }

    /**
     * Tìm tuyến theo ID; nếu không tồn tại thì raise 404 để controller trả về lỗi chuẩn cho client.
     */
    @Transactional(readOnly = true)
    public MetroLine getById(Long id) {
        return lines.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tuyến"));
    }

    /**
     * Tạo tuyến mới với validation bắt buộc: mã tuyến không được trống, tên tuyến không được trống, mã không trùng.
     */
    @Transactional
    public LineResponse create(CreateLineRequest request) {
        if (request.code() == null || request.code().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã tuyến không được để trống");
        }
        if (request.nameLine() == null || request.nameLine().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên tuyến không được để trống");
        }
        String code = request.code().trim();
        if (lines.findByCode(code).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã tuyến đã tồn tại");
        }
        String colorHex = request.colorHex() == null || request.colorHex().isBlank() ? null : request.colorHex().trim();
        String description = request.description() == null || request.description().isBlank() ? null : request.description();
        MetroLine line = new MetroLine(code, request.nameLine().trim(), colorHex, description, request.status());
        return LineResponse.of(lines.save(line));
    }

    /**
     * Cập nhật tuyến hiện có, giữ nguyên dữ liệu nếu client không gửi trường nào.
     * Dùng để sửa tên, màu, mô tả hoặc trạng thái hoạt động mà không phá vỡ bản ghi cũ.
     */
    @Transactional
    public LineResponse update(Long lineId, UpdateLineRequest request) {
        MetroLine line = getById(lineId);
        if (request.code() != null && !request.code().isBlank()) {
            String newCode = request.code().trim();
            lines.findByCode(newCode).ifPresent(existing -> {
                if (!existing.getId().equals(lineId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã tuyến đã tồn tại");
                }
            });
            line.setCode(newCode);
        }
        if (request.nameLine() != null && !request.nameLine().isBlank()) {
            line.setNameLine(request.nameLine().trim());
        }
        // null = giữ nguyên; chuỗi rỗng = xóa giá trị.
        if (request.colorHex() != null) {
            line.setColorHex(request.colorHex().isBlank() ? null : request.colorHex().trim());
        }
        if (request.description() != null) {
            line.setDescription(request.description().isBlank() ? null : request.description());
        }
        if (request.status() != null) {
            line.setStatus(request.status());
        }
        return LineResponse.of(lines.save(line));
    }

    /**
     * Xóa tuyến. Phương thức này chủ yếu đảm bảo dữ liệu đã tồn tại trước khi xoá để tránh lỗi 404.
     */
    @Transactional
    public void delete(Long id) {
        if (!lines.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tuyến");
        }
        lines.deleteById(id);
    }

    /**
     * Trả về toàn bộ ga của tuyến theo thứ tự station_order để frontend hiển thị tuyến theo trật tự đi từ đầu đến cuối.
     */
    @Transactional(readOnly = true)
    public List<LineStationResponse> stations(Long lineId) {
        getById(lineId);
        return lineStationRepository.findByLineIdOrderByStationOrderAsc(lineId).stream()
                .map(LineStationResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LineStationResponse> stationsByCode(String lineCode) {
        MetroLine line = lines.findByCode(lineCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tuyến"));
        return lineStationRepository.findByLineIdOrderByStationOrderAsc(line.getId()).stream()
                .map(LineStationResponse::of)
                .toList();
    }

    /**
     * Trả về các điểm chuyển tuyến của tuyến để tính đường đi giữa các tuyến khác nhau của Metro.
     */
    @Transactional(readOnly = true)
    public List<LineTransferResponse> transfers(Long lineId) {
        getById(lineId);
        return lineTransferRepository.findByFromLineIdOrToLineId(lineId, lineId).stream()
                .map(LineTransferResponse::of)
                .toList();
    }
}
