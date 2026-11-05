package com.metroticket_api.metro.operation;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.common.OperationConfigStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.line.MetroLineRepository;
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
    private final WorkflowEngineService workflowEngine;

    public OperationConfigService(OperationConfigRepository configs, MetroLineRepository lines,
            WorkflowEngineService workflowEngine) {
        this.configs = configs;
        this.lines = lines;
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
        if (request.numberOfTrains() == null || request.headwayMinutes() == null || request.runningTimeMinutes() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thiếu thông tin cấu hình vận hành");
        }

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
        return OperationConfigResponse.of(configs.save(config));
    }

    /**
     * Cập nhật cấu hình hiện có để sửa phiên bản, thời lượng hoặc khoảng cách giữa các chuyến tàu.
     * Nếu bản hiện tại đã được phê duyệt, tạo một bản mới ở trạng thái DRAFT với version tăng lên để tránh sửa trực tiếp bản hiệu lực.
     */
    @Transactional
    public OperationConfigResponse update(Long configId, UpdateOperationConfigRequest request) {
        OperationConfig config = getById(configId);

        if (config.getStatus() == OperationConfigStatus.PENDING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Không thể sửa cấu hình đang chờ phê duyệt");
        }

        if (config.getStatus() == OperationConfigStatus.APPROVED) {
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
}
