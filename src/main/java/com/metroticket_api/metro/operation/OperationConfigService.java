package com.metroticket_api.metro.operation;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.common.OperationConfigStatus;
import com.metroticket_api.metro.common.TrainStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.station.LineStation;
import com.metroticket_api.metro.station.LineStationRepository;
import com.metroticket_api.metro.train.Train;
import com.metroticket_api.metro.train.TrainRepository;
import com.metroticket_api.metro.operation.OperationConfigDtos.CreateOperationConfigRequest;
import com.metroticket_api.metro.operation.OperationConfigDtos.OperationConfigResponse;
import com.metroticket_api.metro.operation.OperationConfigDtos.UpdateOperationConfigRequest;
import com.metroticket_api.workflow.WorkflowEngineService;
import com.metroticket_api.workflow.WorkflowEntityType;

/**
 * Service xử lý cấu hình vận hành tuyến Metro.
 *
 * <p>Class này chịu trách nhiệm lưu lại phiên bản cấu hình, xác thực dữ liệu đầu vào và thực hiện quy trình
 * submit/approve/reject/activate theo mô hình quản lý vận hành Metro chuẩn.</p>
 */
@Service
public class OperationConfigService {

    private final OperationConfigRepository configs;
    private final MetroLineRepository lines;
    private final TrainRepository trains;
    private final LineStationRepository lineStations;
    private final WorkflowEngineService workflowEngine;

    public OperationConfigService(OperationConfigRepository configs, MetroLineRepository lines, TrainRepository trains,
            LineStationRepository lineStations, WorkflowEngineService workflowEngine) {
        this.configs = configs;
        this.lines = lines;
        this.trains = trains;
        this.lineStations = lineStations;
        this.workflowEngine = workflowEngine;
    }

    /**
     * Liệt kê cấu hình vận hành của một tuyến theo thời điểm hiệu lực giảm dần để thuận tiện cho người duyệt xem phiên bản mới nhất trước.
     */
    @Transactional(readOnly = true)
    public List<OperationConfigResponse> listByLine(Long lineId) {
        return configs.findByLineIdOrderByEffectiveFromDesc(lineId).stream().map(OperationConfigResponse::of).toList();
    }

    /**
     * Lấy toàn bộ cấu hình vận hành trên hệ thống, phục vụ tra cứu và dashboard quản lý.
     */
    @Transactional(readOnly = true)
    public List<OperationConfigResponse> listAll() {
        return configs.findAll().stream().map(OperationConfigResponse::of).toList();
    }

    /**
     * Tìm cấu hình theo ID để thực hiện submit, approve, reject hoặc activate trong quy trình quản lý vận hành.
     */
    @Transactional(readOnly = true)
    public OperationConfig getById(Long id) {
        return configs.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy cấu hình vận hành"));
    }

    /**
     * Tạo một bản cấu hình vận hành mới cho tuyến. Version được tính tự động theo phiên bản cao nhất hiện tại của tuyến + 1.
     */
    @Transactional
    public OperationConfigResponse create(Long lineId, CreateOperationConfigRequest request) {
        MetroLine line = lines.findById(lineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không tìm thấy tuyến"));

        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Yêu cầu cấu hình vận hành không được rỗng");
        }
        validateConfigValues(lineId, request.numberOfTrains(), request.headwayMinutes(), request.runningTimeMinutes(),
                request.turnaroundTimeMinutes(), request.startTime(), request.endTime(), request.effectiveFrom(),
                request.effectiveTo());

        Integer nextVersion = configs.findTopByLineIdOrderByVersionDesc(lineId)
                .map(OperationConfig::getVersion)
                .orElse(0) + 1;

        OperationConfig config = new OperationConfig(
                line,
                nextVersion,
                request.numberOfTrains(),
                request.headwayMinutes(),
                request.runningTimeMinutes(),
                request.turnaroundTimeMinutes(),
                request.startTime(),
                request.endTime(),
                request.effectiveFrom(),
                request.effectiveTo(),
                request.createdBy(),
                OperationConfigStatus.DRAFT);
        validateOperationalFeasibility(config);
        return OperationConfigResponse.of(configs.save(config));
    }

    /**
     * Cập nhật cấu hình hiện có để sửa phiên bản, thời lượng hoặc khoảng cách giữa các chuyến tàu.
     * Nếu bản hiện tại đã được phê duyệt, tạo một bản mới ở trạng thái DRAFT với version tăng lên để tránh sửa trực tiếp bản hiệu lực.
     */
    @Transactional
    public OperationConfigResponse update(Long configId, UpdateOperationConfigRequest request) {
        OperationConfig config = getById(configId);

        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Yêu cầu cập nhật cấu hình không được rỗng");
        }

        if (config.getStatus() == OperationConfigStatus.PENDING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Không thể sửa cấu hình đang chờ phê duyệt");
        }

        if (config.getStatus() == OperationConfigStatus.APPROVED || config.getStatus() == OperationConfigStatus.ACTIVE
                || config.getStatus() == OperationConfigStatus.INACTIVE) {
            OperationConfig draft = new OperationConfig(
                    config.getLine(),
                    (config.getVersion() == null ? 0 : config.getVersion()) + 1,
                    request.numberOfTrains() != null ? request.numberOfTrains() : config.getNumberOfTrains(),
                    request.headwayMinutes() != null ? request.headwayMinutes() : config.getHeadwayMinutes(),
                    request.runningTimeMinutes() != null ? request.runningTimeMinutes() : config.getRunningTimeMinutes(),
                    request.turnaroundTimeMinutes() != null ? request.turnaroundTimeMinutes() : config.getTurnaroundTimeMinutes(),
                    request.startTime() != null ? request.startTime() : config.getStartTime(),
                    request.endTime() != null ? request.endTime() : config.getEndTime(),
                    request.effectiveFrom() != null ? request.effectiveFrom() : config.getEffectiveFrom(),
                    request.effectiveTo() != null ? request.effectiveTo() : config.getEffectiveTo(),
                    request.createdBy() != null ? request.createdBy() : config.getCreatedBy(),
                    OperationConfigStatus.DRAFT);
            draft.setApprovedBy(null);
            draft.setApprovedAt(null);
            draft.setSubmittedAt(null);
            draft.setRejectionReason(null);
            validateOperationalFeasibility(draft);
            return OperationConfigResponse.of(configs.save(draft));
        }

        if (request.numberOfTrains() != null) config.setNumberOfTrains(request.numberOfTrains());
        if (request.headwayMinutes() != null) config.setHeadwayMinutes(request.headwayMinutes());
        if (request.runningTimeMinutes() != null) config.setRunningTimeMinutes(request.runningTimeMinutes());
        if (request.turnaroundTimeMinutes() != null) config.setTurnaroundTimeMinutes(request.turnaroundTimeMinutes());
        if (request.startTime() != null) config.setStartTime(request.startTime());
        if (request.endTime() != null) config.setEndTime(request.endTime());
        if (request.effectiveFrom() != null) config.setEffectiveFrom(request.effectiveFrom());
        if (request.effectiveTo() != null) config.setEffectiveTo(request.effectiveTo());
        if (request.createdBy() != null) config.setCreatedBy(request.createdBy());
        validateOperationalFeasibility(config);
        return OperationConfigResponse.of(configs.save(config));
    }

    /**
     * Quy trình duyệt cấu hình vận hành: Draft -> PendingApproval -> Approved/Rejected.
     * Khi đã được phê duyệt, cấu hình mới được coi là phiên bản hiệu lực hiện tại của tuyến.
     */
    @Transactional
    public OperationConfigResponse submit(Long configId) {
        OperationConfig config = getById(configId);
        workflowEngine.ensureTransitionAllowed(
                WorkflowEntityType.OPERATION_CONFIG,
                config.getStatus().name(),
                OperationConfigStatus.PENDING_APPROVAL.name());
        validateOperationalFeasibility(config);
        config.setStatus(OperationConfigStatus.PENDING_APPROVAL);
        config.setSubmittedAt(Instant.now());
        return OperationConfigResponse.of(configs.save(config));
    }

    /**
     * Phê duyệt cấu hình và ghi nhận người phê duyệt cũng như thời điểm thực thi.
     */
    @Transactional
    public OperationConfigResponse approve(Long configId, java.util.UUID approvedBy) {
        OperationConfig config = getById(configId);
        workflowEngine.ensureTransitionAllowed(
                WorkflowEntityType.OPERATION_CONFIG,
                config.getStatus().name(),
                OperationConfigStatus.APPROVED.name());
        validateOperationalFeasibility(config);
        config.setApprovedBy(approvedBy);
        config.setApprovedAt(Instant.now());
        config.setStatus(OperationConfigStatus.APPROVED);
        config.setRejectionReason(null);
        return OperationConfigResponse.of(configs.save(config));
    }

    /**
     * Từ chối cấu hình và giữ nguyên nhân từ chối để người tạo có thể sửa lại.
     */
    @Transactional
    public OperationConfigResponse reject(Long configId, String reason) {
        OperationConfig config = getById(configId);
        workflowEngine.ensureTransitionAllowed(
                WorkflowEntityType.OPERATION_CONFIG,
                config.getStatus().name(),
                OperationConfigStatus.REJECTED.name());
        config.setStatus(OperationConfigStatus.REJECTED);
        config.setRejectionReason(reason);
        return OperationConfigResponse.of(configs.save(config));
    }

    @Transactional
    public OperationConfigResponse activate(Long configId) {
        OperationConfig config = getById(configId);
        workflowEngine.ensureTransitionAllowed(
                WorkflowEntityType.OPERATION_CONFIG,
                config.getStatus().name(),
                OperationConfigStatus.ACTIVE.name());
        ensureOnlyOneActiveConfigForLine(config.getLine().getId(), config.getId());
        config.setStatus(OperationConfigStatus.ACTIVE);
        return OperationConfigResponse.of(configs.save(config));
    }

    @Transactional
    public OperationConfigResponse deactivate(Long configId) {
        OperationConfig config = getById(configId);
        workflowEngine.ensureTransitionAllowed(
                WorkflowEntityType.OPERATION_CONFIG,
                config.getStatus().name(),
                OperationConfigStatus.INACTIVE.name());
        config.setStatus(OperationConfigStatus.INACTIVE);
        return OperationConfigResponse.of(configs.save(config));
    }

    private void validateConfigValues(Long lineId, Integer numberOfTrains, Integer headwayMinutes, Integer runningTimeMinutes,
            Integer turnaroundTimeMinutes, java.time.LocalTime startTime, java.time.LocalTime endTime,
            Instant effectiveFrom, Instant effectiveTo) {
        if (lineId == null || lineId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lineId không hợp lệ");
        }
        if (numberOfTrains == null || numberOfTrains <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "number_of_trains phải lớn hơn 0");
        }
        if (headwayMinutes == null || headwayMinutes <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "headway_minutes phải lớn hơn 0");
        }
        if (runningTimeMinutes == null || runningTimeMinutes <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "running_time_minutes phải lớn hơn 0");
        }
        if (turnaroundTimeMinutes != null && turnaroundTimeMinutes < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "turnaround_time_minutes không được âm");
        }
        if (startTime == null || endTime == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "start_time và end_time không được để trống");
        }
        if (effectiveFrom == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "effective_from không được để trống");
        }
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "effective_to phải lớn hơn hoặc bằng effective_from");
        }
    }

    private void validateOperationalFeasibility(OperationConfig config) {
        if (config == null || config.getLine() == null || config.getLine().getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cấu hình vận hành phải thuộc về một tuyến hợp lệ");
        }

        Long lineId = config.getLine().getId();
        List<LineStation> orderedStations = lineStations.findByLineIdOrderByStationOrderAsc(lineId);
        if (orderedStations == null || orderedStations.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tuyến chưa có ga nào nên không thể tạo cấu hình vận hành");
        }

        long readyTrainCount = trains.findByLineId(lineId).stream()
                .filter(train -> train.getStatus() == TrainStatus.READY || train.getStatus() == TrainStatus.IN_OPERATION)
                .count();
        if (config.getNumberOfTrains() > readyTrainCount) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Số lượng tàu yêu cầu " + config.getNumberOfTrains() + " vượt quá số tàu đang sẵn sàng trên tuyến (" + readyTrainCount + ")");
        }

        if (config.getHeadwayMinutes() == null || config.getHeadwayMinutes() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "headway_minutes phải lớn hơn 0");
        }
        if (config.getRunningTimeMinutes() == null || config.getRunningTimeMinutes() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "running_time_minutes phải lớn hơn 0");
        }
        if (config.getTurnaroundTimeMinutes() == null || config.getTurnaroundTimeMinutes() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "turnaround_time_minutes không được âm");
        }

        long routeTravelSeconds = orderedStations.stream()
                .mapToLong(station -> Optional.ofNullable(station.getTravelTimeFromPrevSeconds()).orElse(0))
                .sum();
        long dwellSeconds = orderedStations.stream()
                .mapToLong(station -> Optional.ofNullable(station.getDwellTimeSeconds()).orElse(30))
                .sum();
        long requiredRouteSeconds = routeTravelSeconds + dwellSeconds + ((long) config.getTurnaroundTimeMinutes() * 60L);
        long runningSeconds = (long) config.getRunningTimeMinutes() * 60L;
        if (runningSeconds < requiredRouteSeconds) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "running_time_minutes không đủ cho hành trình từ ga đầu đến ga cuối, thời gian dừng và quay đầu của tuyến");
        }

        long throughputDemand = (long) config.getNumberOfTrains() * config.getHeadwayMinutes();
        long scheduleCapacity = config.getRunningTimeMinutes() + config.getTurnaroundTimeMinutes();
        if (throughputDemand > scheduleCapacity) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "headway_minutes và số lượng tàu không hợp lý với running_time_minutes + turnaround_time_minutes");
        }
    }

    private void ensureOnlyOneActiveConfigForLine(Long lineId, Long configId) {
        List<OperationConfig> activeConfigs = configs.findByLineIdAndStatus(lineId, OperationConfigStatus.ACTIVE);
        boolean hasAnotherActiveConfig = activeConfigs.stream().anyMatch(config -> !config.getId().equals(configId));
        if (hasAnotherActiveConfig) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tuyến đã có một cấu hình ACTIVE khác. Chỉ được phép có 1 cấu hình ACTIVE trên cùng tuyến.");
        }
    }
}
