package com.metroticket_api.student;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.metroticket_api.student.StudentDtos.AdminVerificationResponse;
import com.metroticket_api.student.StudentDtos.ApproveRequest;
import com.metroticket_api.student.StudentDtos.MyVerificationResponse;
import com.metroticket_api.student.StudentDtos.PolicyResponse;
import com.metroticket_api.student.StudentDtos.ReasonRequest;
import com.metroticket_api.student.StudentDtos.StudentStatusResponse;
import com.metroticket_api.student.StudentDtos.UpdatePolicyRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@org.springframework.validation.annotation.Validated
@Tag(name = "Student verification", description = "Xác thực sinh viên bằng AI (Gemini) + duyệt tay, ưu đãi vé sinh viên")
public class StudentVerificationController {

    private final StudentVerificationService service;
    private final StudentPolicyService policyService;

    public StudentVerificationController(StudentVerificationService service, StudentPolicyService policyService) {
        this.service = service;
        this.policyService = policyService;
    }

    // ===== Sinh viên =====

    @Operation(summary = "Nộp hồ sơ sinh viên", description = "multipart: image (JPEG/PNG/WebP ≤ 5MB), fullName, studentCode, "
            + "school, consent=true. Trả 202; kết quả gửi qua thông báo, app có thể gọi lại GET /me/student-status.")
    @PreAuthorize("hasAuthority('TICKET_BUY')")
    @PostMapping(path = "/me/student-verifications", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MyVerificationResponse submit(@RequestParam MultipartFile image,
            @RequestParam @NotBlank @Size(max = 120) String fullName,
            @RequestParam @NotBlank @Size(max = 30) String studentCode,
            @RequestParam @NotBlank @Size(max = 200) String school,
            @RequestParam(defaultValue = "false") boolean consent) throws IOException {
        return service.submit(fullName, studentCode, school, consent, image.getBytes());
    }

    @GetMapping("/me/student-status")
    public StudentStatusResponse myStatus() {
        return service.myStatus();
    }

    @GetMapping("/me/student-verifications")
    public List<MyVerificationResponse> myHistory() {
        return service.myHistory();
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/me/student-verifications/{id}/cancel")
    public MyVerificationResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }

    // ===== Duyệt tay: ADMIN, MANAGER =====

    @Operation(summary = "Hàng chờ duyệt", description = "status=NEEDS_REVIEW (mặc định nên lọc), sắp xếp hồ sơ cũ nhất trước.")
    @PreAuthorize("hasAuthority('STUDENT_VERIFY')")
    @GetMapping("/admin/student-verifications")
    public Page<AdminVerificationResponse> list(@RequestParam(required = false) StudentVerificationStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return service.list(status, pageable);
    }

    @PreAuthorize("hasAuthority('STUDENT_VERIFY')")
    @GetMapping("/admin/student-verifications/{id}")
    public AdminVerificationResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** Ảnh thẻ là dữ liệu cá nhân: không cache, chỉ người có STUDENT_VERIFY. */
    @PreAuthorize("hasAuthority('STUDENT_VERIFY')")
    @GetMapping("/admin/student-verifications/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable UUID id) {
        StudentVerificationImage img = service.image(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(detectMime(img.getContent(), null)))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(img.getContent());
    }

    @PreAuthorize("hasAuthority('STUDENT_VERIFY')")
    @PostMapping("/admin/student-verifications/{id}/approve")
    public AdminVerificationResponse approve(@PathVariable UUID id, @Valid @RequestBody(required = false) ApproveRequest request) {
        return service.approve(id, request == null ? null : request.approvedUntil(), request == null ? null : request.note());
    }

    @PreAuthorize("hasAuthority('STUDENT_VERIFY')")
    @PostMapping("/admin/student-verifications/{id}/reject")
    public AdminVerificationResponse reject(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return service.reject(id, request.reason());
    }

    @PreAuthorize("hasAuthority('STUDENT_VERIFY')")
    @PostMapping("/admin/student-verifications/{id}/revoke")
    public AdminVerificationResponse revoke(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return service.revoke(id, request.reason());
    }

    @Operation(summary = "Gửi lại cho AI đọc", description = "Hồ sơ NEEDS_REVIEW vì AI lỗi/chưa cấu hình.")
    @PreAuthorize("hasAuthority('STUDENT_VERIFY')")
    @PostMapping("/admin/student-verifications/{id}/retry-ai")
    public AdminVerificationResponse retryAi(@PathVariable UUID id) {
        return service.retryAi(id);
    }

    // ===== Điều kiện xác thực =====

    @PreAuthorize("hasAnyAuthority('STUDENT_POLICY_MANAGE', 'STUDENT_VERIFY')")
    @GetMapping("/admin/student-policy")
    public PolicyResponse policy() {
        return policyService.get();
    }

    @Operation(summary = "Nhập điều kiện xác thực sinh viên", description = "Gửi version hiện tại; field null giữ nguyên.")
    @PreAuthorize("hasAuthority('STUDENT_POLICY_MANAGE')")
    @PutMapping("/admin/student-policy")
    public PolicyResponse updatePolicy(@Valid @RequestBody UpdatePolicyRequest request) {
        return policyService.update(request);
    }

    private static String detectMime(byte[] content, String fallback) {
        try {
            return StudentVerificationService.detectImageType(content);
        } catch (RuntimeException e) {
            return fallback == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : fallback;
        }
    }
}
