package com.metroticket_api.metro.train;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.common.TrainStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.train.TrainDtos.CreateTrainRequest;
import com.metroticket_api.metro.train.TrainDtos.TrainResponse;
import com.metroticket_api.metro.train.TrainDtos.UpdateTrainRequest;

/**
 * Service xử lý nghiệp vụ đoàn tàu Metro.
 *
 * <p>Class này đảm bảo dữ liệu tàu hợp lệ, liên kết đúng tuyến, và hỗ trợ cập nhật trạng thái vận hành.
 */
@Service
public class TrainService {

    private final TrainRepository trains;
    private final MetroLineRepository lines;

    public TrainService(TrainRepository trains, MetroLineRepository lines) {
        this.trains = trains;
        this.lines = lines;
    }

    /**
     * Lấy danh sách tàu theo phân trang, phục vụ màn hình quản lý phương tiện và giám sát hàng ngày.
     */
    @Transactional(readOnly = true)
    public Page<TrainResponse> list(Pageable pageable) {
        return trains.findAll(pageable).map(TrainResponse::of);
    }

    /**
     * Tìm tàu theo ID; nếu không tìm thấy thì trả về 404 để client biết dữ liệu không tồn tại.
     */
    @Transactional(readOnly = true)
    public Train getById(Long id) {
        return trains.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tàu"));
    }

    /**
     * Liệt kê tất cả tàu thuộc một tuyến để hiển thị trên sơ đồ vận hành tuyến và theo dõi hoạt động.
     */
    @Transactional(readOnly = true)
    public List<TrainResponse> listByLine(Long lineId) {
        return trains.findByLineId(lineId).stream().map(TrainResponse::of).toList();
    }

    /**
     * Tạo đoàn tàu mới. Hàm này kiểm tra mã tàu duy nhất, tuyến gán và các trường bắt buộc như mã, trạng thái.
     */
    @Transactional
    public TrainResponse create(CreateTrainRequest request) {
        if (request.code() == null || request.code().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã tàu không được để trống");
        }
        if (trains.existsByCode(request.code())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã tàu đã tồn tại");
        }
        MetroLine line = null;
        if (request.lineId() != null) {
            line = lines.findById(request.lineId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không tìm thấy tuyến"));
        }
        Train train = new Train(request.code(), request.nameTrain(), line, request.capacity(), request.manufactureYear(), request.status());
        return TrainResponse.of(trains.save(train));
    }

    /**
     * Cập nhật thông tin tàu hiện có, hỗ trợ thay đổi tuyến, sức chứa hoặc trạng thái hoạt động.
     */
    @Transactional
    public TrainResponse update(Long trainId, UpdateTrainRequest request) {
        Train train = getById(trainId);
        if (request.code() != null && !request.code().isBlank()) {
            if (trains.existsByCode(request.code()) && !request.code().equals(train.getCode())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã tàu đã tồn tại");
            }
            train.setCode(request.code());
        }
        if (request.nameTrain() != null) {
            train.setNameTrain(request.nameTrain());
        }
        if (request.lineId() != null) {
            MetroLine line = lines.findById(request.lineId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không tìm thấy tuyến"));
            train.setLine(line);
        }
        if (request.capacity() != null) {
            train.setCapacity(request.capacity());
        }
        if (request.manufactureYear() != null) {
            train.setManufactureYear(request.manufactureYear());
        }
        if (request.status() != null) {
            train.setStatus(request.status());
        }
        return TrainResponse.of(trains.save(train));
    }

    /**
     * Xóa thông tin tàu sau khi kiểm tra dữ liệu tồn tại để tránh xoá nhầm.
     */
    @Transactional
    public void delete(Long trainId) {
        if (!trains.existsById(trainId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tàu");
        }
        trains.deleteById(trainId);
    }

    /**
     * Cập nhật trạng thái vận hành của tàu để phản ánh tình hình thực tế: sẵn sàng, đang chạy, bảo trì hoặc ngừng phục vụ.
     */
    @Transactional
    public TrainResponse updateStatus(Long trainId, TrainStatus status) {
        Train train = getById(trainId);
        train.setStatus(status);
        return TrainResponse.of(trains.save(train));
    }
}
