package com.metroticket_api.metro.line;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.common.MetroLineStatus;
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

    public MetroLineService(MetroLineRepository lines,
            LineStationRepository lineStationRepository,
            LineTransferRepository lineTransferRepository) {
        this.lines = lines;
        this.lineStationRepository = lineStationRepository;
        this.lineTransferRepository = lineTransferRepository;
    }

    /**
     * Lọc tuyến theo mã, tên hoặc trạng thái; API này thường được dùng cho màn hình quản lý tuyến.
     * Mặc dù có thể chèn logic filter theo repository, phần này giữ dữ liệu dễ đọc và không cần thêm query phức tạp.
     */
    /**
     * Lấy danh sách tuyến theo bộ lọc cho màn hình quản lý tuyến hoặc danh mục tuyến trên ứng dụng.
     * Nếu client gửi tham số code/name/status, hệ thống sẽ lọc theo cách phù hợp và trả về Page chuẩn.
     */
    @Transactional(readOnly = true)
    public Page<LineResponse> list(String code, String name, MetroLineStatus status, Pageable pageable) {
        if (code != null && !code.isBlank()) {
            List<MetroLine> matches = new ArrayList<>();
            for (MetroLine line : lines.findAll()) {
                if (line.getCode().equalsIgnoreCase(code)
                        || (name != null && !name.isBlank() && line.getNameLine().toLowerCase().contains(name.toLowerCase()))) {
                    matches.add(line);
                }
            }
            return new PageImpl<>(matches.stream().map(LineResponse::of).toList(), pageable, matches.size());
        }
        if (status != null) {
            return lines.findAllByStatus(status, pageable).map(LineResponse::of);
        }
        if (name != null && !name.isBlank()) {
            return lines.findByNameLineContainingIgnoreCase(name, pageable).map(LineResponse::of);
        }
        return lines.findAll(pageable).map(LineResponse::of);
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
        if (lines.findByCode(request.code()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã tuyến đã tồn tại");
        }
        MetroLine line = new MetroLine(request.code(), request.nameLine(), request.colorHex(), request.description(), request.status());
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
            lines.findByCode(request.code()).ifPresent(existing -> {
                if (!existing.getId().equals(lineId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã tuyến đã tồn tại");
                }
            });
            line.setCode(request.code());
        }
        if (request.nameLine() != null && !request.nameLine().isBlank()) {
            line.setNameLine(request.nameLine());
        }
        line.setColorHex(request.colorHex());
        line.setDescription(request.description());
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
