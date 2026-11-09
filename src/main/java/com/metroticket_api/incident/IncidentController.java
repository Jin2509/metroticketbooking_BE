package com.metroticket_api.incident;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.metroticket_api.content.ObjectStorage;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Incidents", description = "UC19: báo cáo và xử lý sự cố tại ga")
public class IncidentController {

    public record ReportIncidentRequest(@NotNull Long stationId, @NotNull Incident.Type type,
            @NotNull Incident.Severity severity, @NotBlank @Size(max = 2000) String description) {
    }

    public record ResolveIncidentRequest(@NotBlank @Size(max = 2000) String resolution) {
    }

    public record IncidentView(Long id, Long stationId, String stationName, UUID reportedBy, Incident.Type type,
            Incident.Severity severity, String description, boolean hasImage, Incident.Status status, UUID handledBy,
            String resolution, Instant acceptedAt, Instant resolvedAt, Instant createdAt) {
        static IncidentView of(Incident i) {
            return new IncidentView(i.getId(), i.getStation().getId(), i.getStation().getNameStation(), i.getReportedBy(),
                    i.getType(), i.getSeverity(), i.getDescription(), i.getImageKey() != null, i.getStatus(), i.getHandledBy(),
                    i.getResolution(), i.getAcceptedAt(), i.getResolvedAt(), i.getCreatedAt());
        }
    }

    private final IncidentService service;

    public IncidentController(IncidentService service) {
        this.service = service;
    }

    @PreAuthorize("hasAuthority('INCIDENT_REPORT')")
    @PostMapping(path = "/incidents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentView report(@Valid @RequestPart("data") ReportIncidentRequest data,
            @RequestPart(value = "image", required = false) MultipartFile image) {
        return IncidentView.of(service.report(data.stationId(), data.type(), data.severity(), data.description(), image));
    }

    @PreAuthorize("hasAuthority('INCIDENT_REPORT')")
    @GetMapping("/me/incidents")
    public Page<IncidentView> mine(@PageableDefault(size = 20) Pageable pageable) {
        return service.mine(pageable).map(IncidentView::of);
    }

    @PreAuthorize("hasAuthority('INCIDENT_MANAGE')")
    @GetMapping("/incidents")
    public Page<IncidentView> list(@RequestParam(required = false) Incident.Status status,
            @PageableDefault(size = 20) Pageable pageable) {
        return service.list(status, pageable).map(IncidentView::of);
    }

    @PreAuthorize("hasAuthority('INCIDENT_MANAGE')")
    @PostMapping("/incidents/{id}/accept")
    public IncidentView accept(@PathVariable Long id) {
        return IncidentView.of(service.accept(id));
    }

    @PreAuthorize("hasAuthority('INCIDENT_MANAGE')")
    @PostMapping("/incidents/{id}/resolve")
    public IncidentView resolve(@PathVariable Long id, @Valid @RequestBody ResolveIncidentRequest request) {
        return IncidentView.of(service.resolve(id, request.resolution()));
    }

    @PreAuthorize("hasAnyAuthority('INCIDENT_REPORT', 'INCIDENT_MANAGE')")
    @GetMapping("/incidents/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        ObjectStorage.StoredObject image = service.image(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, image.contentType())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(image.content());
    }
}
