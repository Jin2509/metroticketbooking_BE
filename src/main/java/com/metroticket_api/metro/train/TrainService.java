package com.metroticket_api.metro.train;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.common.MetroLineStatus;
import com.metroticket_api.metro.common.OperationConfigStatus;
import com.metroticket_api.metro.common.SortGuard;
import com.metroticket_api.metro.common.TrainStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.operation.OperationConfig;
import com.metroticket_api.metro.operation.OperationConfigRepository;
import com.metroticket_api.metro.train.TrainDtos.CreateTrainRequest;
import com.metroticket_api.metro.train.TrainDtos.TrainResponse;
import com.metroticket_api.metro.train.TrainDtos.UpdateTrainRequest;

/**
 * Nghiệp vụ đoàn tàu.
 *
 * <ul>
 *   <li>Mã tàu duy nhất; trạng thái chỉ đổi theo {@link TrainStatus#allowedNext()}.</li>
 *   <li>IN_OPERATION chỉ khi tàu đã được gán vào một tuyến ACTIVE.</li>
 *   <li>Thao tác hành chính (xoá tàu, chuyển tuyến) không được làm cấu hình ACTIVE của tuyến thiếu tàu.
 *       Đổi trạng thái do sự cố (MAINTENANCE / OUT_OF_SERVICE) luôn được phép vì lý do an toàn;
 *       mô phỏng sẽ chạy với số tàu còn lại.</li>
 * </ul>
 */
@Service
public class TrainService {

    public static final Set<String> SORTABLE_FIELDS = Set.of("id", "code", "nameTrain", "status", "capacity",
            "manufactureYear", "createdAt", "updatedAt");

    private final TrainRepository trains;
    private final MetroLineRepository lines;
    private final OperationConfigRepository configs;

    public TrainService(TrainRepository trains, MetroLineRepository lines, OperationConfigRepository configs) {
        this.trains = trains;
        this.lines = lines;
        this.configs = configs;
    }

    @Transactional(readOnly = true)
    public Page<TrainResponse> list(Long lineId, TrainStatus status, String q, Pageable pageable) {
        SortGuard.requireAllowed(pageable, SORTABLE_FIELDS);
        Specification<Train> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (lineId != null) {
                predicates.add(cb.equal(root.get("line").get("id"), lineId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("code")), like),
                        cb.like(cb.lower(root.get("nameTrain")), like)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return trains.findAll(spec, pageable).map(TrainResponse::of);
    }

    @Transactional(readOnly = true)
    public List<TrainResponse> listAll() {
        return trains.findAll(Sort.by("code")).stream().map(TrainResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public Train getById(Long id) {
        return trains.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tàu"));
    }

    @Transactional(readOnly = true)
    public TrainResponse get(Long id) {
        return TrainResponse.of(getById(id));
    }

    @Transactional(readOnly = true)
    public List<TrainResponse> listByLine(Long lineId) {
        if (!lines.existsById(lineId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tuyến");
        }
        return trains.findByLineId(lineId).stream()
                .sorted(java.util.Comparator.comparing(Train::getCode))
                .map(TrainResponse::of)
                .toList();
    }

    @Transactional
    public TrainResponse create(CreateTrainRequest request) {
        String code = requireCode(request.code());
        if (trains.existsByCode(code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã tàu đã tồn tại");
        }
        MetroLine line = request.lineId() == null ? null : findLine(request.lineId());
        TrainStatus status = request.status() == null ? TrainStatus.READY : request.status();
        if (status == TrainStatus.IN_OPERATION) {
            ensureCanOperate(line);
        }
        Train train = new Train(code, blankToNull(request.nameTrain()), line, request.capacity(),
                request.manufactureYear(), status);
        return TrainResponse.of(trains.save(train));
    }

    @Transactional
    public TrainResponse update(Long trainId, UpdateTrainRequest request) {
        Train train = getById(trainId);
        if (request.code() != null && !request.code().isBlank()) {
            String code = request.code().trim();
            trains.findByCode(code).ifPresent(existing -> {
                if (!existing.getId().equals(trainId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Mã tàu đã tồn tại");
                }
            });
            train.setCode(code);
        }
        if (request.nameTrain() != null) {
            train.setNameTrain(blankToNull(request.nameTrain()));
        }
        if (request.capacity() != null) {
            train.setCapacity(request.capacity());
        }
        if (request.manufactureYear() != null) {
            train.setManufactureYear(request.manufactureYear());
        }
        if (request.lineId() != null && (train.getLine() == null || !request.lineId().equals(train.getLine().getId()))) {
            reassignLine(train, findLine(request.lineId()));
        }
        if (request.status() != null && request.status() != train.getStatus()) {
            changeStatus(train, request.status());
        }
        return TrainResponse.of(trains.save(train));
    }

    @Transactional
    public TrainResponse updateStatus(Long trainId, TrainStatus status) {
        if (status == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trạng thái tàu không được để trống");
        }
        Train train = getById(trainId);
        if (status == train.getStatus()) {
            return TrainResponse.of(train);
        }
        changeStatus(train, status);
        return TrainResponse.of(trains.save(train));
    }

    @Transactional
    public void delete(Long trainId) {
        Train train = getById(trainId);
        if (train.getStatus() == TrainStatus.IN_OPERATION) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tàu đang vận hành, hãy chuyển về READY hoặc OUT_OF_SERVICE trước khi xoá");
        }
        ensureActiveConfigStillFeasibleWithout(train);
        trains.delete(train);
    }

    private void changeStatus(Train train, TrainStatus target) {
        TrainStatus current = train.getStatus();
        if (!current.canTransitionTo(target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Không thể chuyển tàu từ " + current + " sang " + target + ". Cho phép: " + current.allowedNext());
        }
        if (target == TrainStatus.IN_OPERATION) {
            ensureCanOperate(train.getLine());
        }
        train.setStatus(target);
    }

    private void reassignLine(Train train, MetroLine newLine) {
        if (train.getStatus() == TrainStatus.IN_OPERATION) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tàu đang vận hành, không thể chuyển sang tuyến khác");
        }
        ensureActiveConfigStillFeasibleWithout(train);
        train.setLine(newLine);
    }

    private void ensureCanOperate(MetroLine line) {
        if (line == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tàu phải được gán tuyến trước khi vận hành");
        }
        if (line.getStatus() != MetroLineStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tuyến " + line.getCode() + " không ở trạng thái ACTIVE nên tàu không thể vận hành");
        }
    }

    /** Bỏ tàu khỏi tuyến hiện tại (xoá/chuyển tuyến) không được làm cấu hình ACTIVE thiếu tàu sẵn sàng. */
    private void ensureActiveConfigStillFeasibleWithout(Train train) {
        if (train.getLine() == null || !train.getStatus().isAvailableForService()) {
            return;
        }
        Long lineId = train.getLine().getId();
        int required = configs.findByLineIdAndStatus(lineId, OperationConfigStatus.ACTIVE).stream()
                .mapToInt(OperationConfig::getNumberOfTrains)
                .max()
                .orElse(0);
        if (required == 0) {
            return;
        }
        long remaining = trains.findByLineId(lineId).stream()
                .filter(other -> !other.getId().equals(train.getId()))
                .filter(other -> other.getStatus().isAvailableForService())
                .count();
        if (remaining < required) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cấu hình vận hành ACTIVE của tuyến " + train.getLine().getCode() + " cần " + required
                            + " tàu sẵn sàng, bỏ tàu này chỉ còn " + remaining + ". Hãy điều chỉnh cấu hình trước");
        }
    }

    private MetroLine findLine(Long lineId) {
        return lines.findById(lineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không tìm thấy tuyến"));
    }

    private static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã tàu không được để trống");
        }
        return code.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
