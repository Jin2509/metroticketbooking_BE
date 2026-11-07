package com.metroticket_api.metro.operation;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.common.MetroLineStatus;
import com.metroticket_api.metro.common.OperationConfigStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.operation.OperationConfigDtos.CreateOperationConfigRequest;
import com.metroticket_api.metro.operation.OperationConfigDtos.OperationConfigResponse;
import com.metroticket_api.metro.operation.OperationConfigDtos.UpdateOperationConfigRequest;
import com.metroticket_api.metro.station.LineStation;
import com.metroticket_api.metro.station.LineStationRepository;
import com.metroticket_api.metro.train.TrainRepository;
import com.metroticket_api.security.CurrentUser;
import com.metroticket_api.workflow.WorkflowEngineService;
import com.metroticket_api.workflow.WorkflowEntityType;

/**
 * Cấu hình vận hành theo phiên bản (versioned) của tuyến, quy trình maker-checker:
 *
 * <pre>
 * DRAFT ──submit──▶ PENDING_APPROVAL ──approve──▶ APPROVED ──activate──▶ ACTIVE ⇄ INACTIVE
 *   ▲                      │reject                    │deactivate
 *   └──── sửa (update) ── REJECTED                    ▼
 *                                                 INACTIVE
 * </pre>
 *
 * <ul>
 *   <li>Người tạo/duyệt lấy từ JWT. Người duyệt phải khác người soạn (four-eyes).</li>
 *   <li>Sửa bản APPROVED/ACTIVE/INACTIVE không ghi đè mà sinh bản DRAFT mới version = max + 1.</li>
 *   <li>Mỗi tuyến tối đa 1 bản ACTIVE (có partial unique index ở DB). Kích hoạt bản mới tự chuyển bản ACTIVE cũ
 *       sang INACTIVE trong cùng transaction.</li>
 *   <li>Mô hình lịch chạy: number_of_trains × headway = 2 × (running_time + turnaround) (một vòng khứ hồi).</li>
 * </ul>
 */
@Service
public class OperationConfigService {

    private final OperationConfigRepository configs;
    private final MetroLineRepository lines;
    private final TrainRepository trains;
    private final LineStationRepository lineStations;
    private final WorkflowEngineService workflowEngine;
    private final Clock clock;

    public OperationConfigService(OperationConfigRepository configs, MetroLineRepository lines, TrainRepository trains,
            LineStationRepository lineStations, WorkflowEngineService workflowEngine, Clock clock) {
        this.configs = configs;
        this.lines = lines;
        this.trains = trains;
        this.lineStations = lineStations;
        this.workflowEngine = workflowEngine;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<OperationConfigResponse> listByLine(Long lineId) {
        if (!lines.existsById(lineId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tuyến");
        }
        return configs.findByLineIdOrderByEffectiveFromDesc(lineId).stream()
                .sorted(Comparator.comparing(OperationConfig::getVersion).reversed())
                .map(OperationConfigResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OperationConfigResponse> listAll(OperationConfigStatus status) {
        List<OperationConfig> result = status == null ? configs.findAll() : configs.findByStatusOrderByLineIdAscVersionDesc(status);
        return result.stream().map(OperationConfigResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public OperationConfig getById(Long id) {
        return configs.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy cấu hình vận hành"));
    }

    @Transactional(readOnly = true)
    public OperationConfigResponse get(Long id) {
        return OperationConfigResponse.of(getById(id));
    }

    @Transactional
    public OperationConfigResponse create(Long lineId, CreateOperationConfigRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Yêu cầu cấu hình vận hành không được rỗng");
        }
        MetroLine line = lines.findById(lineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tuyến"));

        OperationConfig config = new OperationConfig(
                line,
                nextVersion(lineId),
                request.numberOfTrains(),
                request.headwayMinutes(),
                request.runningTimeMinutes(),
                request.turnaroundTimeMinutes(),
                request.startTime(),
                request.endTime(),
                request.effectiveFrom(),
                request.effectiveTo(),
                CurrentUser.optionalId().orElse(null),
                OperationConfigStatus.DRAFT);
        validateConfigValues(config);
        validateOperationalFeasibility(config);
        return OperationConfigResponse.of(configs.save(config));
    }

    /**
     * DRAFT: sửa trực tiếp. REJECTED: sửa trực tiếp và đưa về DRAFT để gửi duyệt lại.
     * APPROVED/ACTIVE/INACTIVE: không sửa bản đã duyệt mà tạo bản DRAFT mới (version = max + 1).
     * PENDING_APPROVAL: khoá, không cho sửa.
     */
    @Transactional
    public OperationConfigResponse update(Long configId, UpdateOperationConfigRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Yêu cầu cập nhật cấu hình không được rỗng");
        }
        OperationConfig config = getById(configId);
        OperationConfigStatus status = config.getStatus();

        if (status == OperationConfigStatus.PENDING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Không thể sửa cấu hình đang chờ phê duyệt");
        }

        OperationConfig target;
        if (status == OperationConfigStatus.APPROVED || status == OperationConfigStatus.ACTIVE
                || status == OperationConfigStatus.INACTIVE) {
            target = new OperationConfig(
                    config.getLine(),
                    nextVersion(config.getLine().getId()),
                    config.getNumberOfTrains(),
                    config.getHeadwayMinutes(),
                    config.getRunningTimeMinutes(),
                    config.getTurnaroundTimeMinutes(),
                    config.getStartTime(),
                    config.getEndTime(),
                    config.getEffectiveFrom(),
                    config.getEffectiveTo(),
                    CurrentUser.optionalId().orElse(config.getCreatedBy()),
                    OperationConfigStatus.DRAFT);
        } else {
            target = config;
            if (status == OperationConfigStatus.REJECTED) {
                workflowEngine.ensureTransitionAllowed(WorkflowEntityType.OPERATION_CONFIG,
                        status.name(), OperationConfigStatus.DRAFT.name());
                target.setStatus(OperationConfigStatus.DRAFT);
                target.setRejectionReason(null);
                target.setSubmittedAt(null);
            }
        }

        applyChanges(target, request);
        validateConfigValues(target);
        validateOperationalFeasibility(target);
        return OperationConfigResponse.of(configs.save(target));
    }

    @Transactional
    public OperationConfigResponse submit(Long configId) {
        OperationConfig config = getById(configId);
        workflowEngine.ensureTransitionAllowed(WorkflowEntityType.OPERATION_CONFIG,
                config.getStatus().name(), OperationConfigStatus.PENDING_APPROVAL.name());
        validateConfigValues(config);
        validateOperationalFeasibility(config);
        config.setStatus(OperationConfigStatus.PENDING_APPROVAL);
        config.setSubmittedAt(clock.instant());
        return OperationConfigResponse.of(configs.save(config));
    }

    @Transactional
    public OperationConfigResponse approve(Long configId) {
        OperationConfig config = getById(configId);
        workflowEngine.ensureTransitionAllowed(WorkflowEntityType.OPERATION_CONFIG,
                config.getStatus().name(), OperationConfigStatus.APPROVED.name());
        UUID approver = CurrentUser.optionalId().orElse(null);
        ensureNotSelfReview(config, approver);
        validateOperationalFeasibility(config);
        config.setApprovedBy(approver);
        config.setApprovedAt(clock.instant());
        config.setStatus(OperationConfigStatus.APPROVED);
        config.setRejectionReason(null);
        return OperationConfigResponse.of(configs.save(config));
    }

    @Transactional
    public OperationConfigResponse reject(Long configId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phải nhập lý do từ chối");
        }
        OperationConfig config = getById(configId);
        workflowEngine.ensureTransitionAllowed(WorkflowEntityType.OPERATION_CONFIG,
                config.getStatus().name(), OperationConfigStatus.REJECTED.name());
        UUID reviewer = CurrentUser.optionalId().orElse(null);
        ensureNotSelfReview(config, reviewer);
        config.setStatus(OperationConfigStatus.REJECTED);
        config.setRejectionReason(reason.trim());
        config.setApprovedBy(reviewer);
        config.setApprovedAt(null);
        return OperationConfigResponse.of(configs.save(config));
    }

    /**
     * Kích hoạt (go-live). Kiểm tra lại tính khả thi tại thời điểm kích hoạt (số tàu sẵn sàng có thể đã đổi),
     * khung hiệu lực phải bao thời điểm hiện tại, rồi thay thế bản ACTIVE cũ của tuyến.
     */
    @Transactional
    public OperationConfigResponse activate(Long configId) {
        OperationConfig config = getById(configId);
        workflowEngine.ensureTransitionAllowed(WorkflowEntityType.OPERATION_CONFIG,
                config.getStatus().name(), OperationConfigStatus.ACTIVE.name());
        if (config.getLine().getStatus() != MetroLineStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tuyến không ở trạng thái ACTIVE, không thể kích hoạt lịch chạy");
        }
        Instant now = clock.instant();
        if (config.getEffectiveFrom().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cấu hình chưa đến ngày hiệu lực (effective_from)");
        }
        if (config.getEffectiveTo() != null && !config.getEffectiveTo().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cấu hình đã hết hiệu lực (effective_to)");
        }
        validateOperationalFeasibility(config);

        List<OperationConfig> currentActive = configs.findByLineIdAndStatus(config.getLine().getId(), OperationConfigStatus.ACTIVE);
        for (OperationConfig previous : currentActive) {
            if (!previous.getId().equals(config.getId())) {
                previous.setStatus(OperationConfigStatus.INACTIVE);
                // flush trước để không vi phạm partial unique index "1 ACTIVE / tuyến"
                configs.saveAndFlush(previous);
            }
        }
        config.setStatus(OperationConfigStatus.ACTIVE);
        return OperationConfigResponse.of(configs.save(config));
    }

    @Transactional
    public OperationConfigResponse deactivate(Long configId) {
        OperationConfig config = getById(configId);
        workflowEngine.ensureTransitionAllowed(WorkflowEntityType.OPERATION_CONFIG,
                config.getStatus().name(), OperationConfigStatus.INACTIVE.name());
        config.setStatus(OperationConfigStatus.INACTIVE);
        return OperationConfigResponse.of(configs.save(config));
    }

    private int nextVersion(Long lineId) {
        return configs.findTopByLineIdOrderByVersionDesc(lineId)
                .map(OperationConfig::getVersion)
                .orElse(0) + 1;
    }

    private static void applyChanges(OperationConfig config, UpdateOperationConfigRequest request) {
        if (request.numberOfTrains() != null) config.setNumberOfTrains(request.numberOfTrains());
        if (request.headwayMinutes() != null) config.setHeadwayMinutes(request.headwayMinutes());
        if (request.runningTimeMinutes() != null) config.setRunningTimeMinutes(request.runningTimeMinutes());
        if (request.turnaroundTimeMinutes() != null) config.setTurnaroundTimeMinutes(request.turnaroundTimeMinutes());
        if (request.startTime() != null) config.setStartTime(request.startTime());
        if (request.endTime() != null) config.setEndTime(request.endTime());
        if (request.effectiveFrom() != null) config.setEffectiveFrom(request.effectiveFrom());
        if (Boolean.TRUE.equals(request.clearEffectiveTo())) {
            config.setEffectiveTo(null);
        } else if (request.effectiveTo() != null) {
            config.setEffectiveTo(request.effectiveTo());
        }
    }

    private static void ensureNotSelfReview(OperationConfig config, UUID reviewer) {
        if (reviewer != null && Objects.equals(reviewer, config.getCreatedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Người soạn cấu hình không được tự duyệt/từ chối (cần người duyệt khác)");
        }
    }

    private static void validateConfigValues(OperationConfig config) {
        if (config.getNumberOfTrains() == null || config.getNumberOfTrains() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "number_of_trains phải lớn hơn 0");
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
        LocalTime start = config.getStartTime();
        LocalTime end = config.getEndTime();
        if (start == null || end == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "start_time và end_time không được để trống");
        }
        if (start.equals(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "start_time và end_time không được trùng nhau");
        }
        if (config.getEffectiveFrom() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "effective_from không được để trống");
        }
        if (config.getEffectiveTo() != null && config.getEffectiveTo().isBefore(config.getEffectiveFrom())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "effective_to phải lớn hơn hoặc bằng effective_from");
        }
    }

    /**
     * Kiểm tra cấu hình chạy được trên dữ liệu tuyến hiện tại, khớp đúng mô hình của {@code TrainPositionService}.
     */
    private void validateOperationalFeasibility(OperationConfig config) {
        if (config == null || config.getLine() == null || config.getLine().getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cấu hình vận hành phải thuộc về một tuyến hợp lệ");
        }
        Long lineId = config.getLine().getId();
        List<LineStation> orderedStations = lineStations.findByLineIdOrderByStationOrderAsc(lineId);
        if (orderedStations == null || orderedStations.size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tuyến cần có ít nhất hai ga để tạo cấu hình vận hành");
        }

        long readyTrainCount = trains.findByLineId(lineId).stream()
                .filter(train -> train.getStatus() != null && train.getStatus().isAvailableForService())
                .count();
        if (config.getNumberOfTrains() > readyTrainCount) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Số lượng tàu yêu cầu " + config.getNumberOfTrains()
                            + " vượt quá số tàu đang sẵn sàng trên tuyến (" + readyTrainCount + ")");
        }

        long routeTravelSeconds = 0;
        for (int i = 1; i < orderedStations.size(); i++) {
            Integer travel = orderedStations.get(i).getTravelTimeFromPrevSeconds();
            if (travel == null || travel <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Đoạn tới ga thứ " + (i + 1) + " chưa có travel_time_from_prev_seconds hợp lệ (> 0)");
            }
            routeTravelSeconds += travel;
        }
        long dwellSeconds = 0;
        for (int i = 1; i < orderedStations.size() - 1; i++) {
            Integer dwell = orderedStations.get(i).getDwellTimeSeconds();
            dwellSeconds += dwell == null ? 0 : dwell;
        }
        long runningSeconds = config.getRunningTimeMinutes() * 60L;
        if (runningSeconds < routeTravelSeconds + dwellSeconds) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "running_time_minutes (" + config.getRunningTimeMinutes() + " phút) không đủ cho thời gian chạy và dừng giữa các ga ("
                            + Math.ceilDiv(routeTravelSeconds + dwellSeconds, 60) + " phút)");
        }

        long cycleSeconds = 2L * (config.getRunningTimeMinutes() + config.getTurnaroundTimeMinutes()) * 60L;
        long trainHeadwaySeconds = (long) config.getNumberOfTrains() * config.getHeadwayMinutes() * 60L;
        if (trainHeadwaySeconds != cycleSeconds) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "number_of_trains × headway_minutes (" + trainHeadwaySeconds / 60 + ") phải bằng 2 × (running_time + turnaround) ("
                            + cycleSeconds / 60 + " phút)");
        }
    }
}
