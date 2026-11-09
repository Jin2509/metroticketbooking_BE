package com.metroticket_api.system;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/admin/system-params")
@Tag(name = "System params", description = "UC09: tham số hệ thống")
@PreAuthorize("hasAuthority('SYSTEM_PARAM_MANAGE')")
public class SystemParamController {

    public record ParamView(String key, String group, String value, String valueType, String description,
            Instant effectiveFrom, Instant effectiveTo) {
        static ParamView of(SystemParam p) {
            return new ParamView(p.getParamKey(), p.getParamGroup(), p.getParamValue(), p.getValueType(),
                    p.getDescription(), p.getEffectiveFrom(), p.getEffectiveTo());
        }
    }

    public record UpdateParamRequest(@NotNull Integer value, Instant effectiveFrom) {
    }

    private final SystemParamService service;

    public SystemParamController(SystemParamService service) {
        this.service = service;
    }

    @GetMapping
    public List<ParamView> list() {
        return service.latest().stream().map(ParamView::of).toList();
    }

    @GetMapping("/{key}/history")
    public List<ParamView> history(@PathVariable String key) {
        return service.history(key).stream().map(ParamView::of).toList();
    }

    @PutMapping("/{key}")
    public ParamView update(@PathVariable String key, @Valid @RequestBody UpdateParamRequest request) {
        return ParamView.of(service.update(key, request.value(), request.effectiveFrom()));
    }
}
