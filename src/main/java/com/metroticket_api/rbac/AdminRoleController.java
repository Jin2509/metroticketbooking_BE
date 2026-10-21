package com.metroticket_api.rbac;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.rbac.RbacDtos.CreateRoleRequest;
import com.metroticket_api.rbac.RbacDtos.PermissionView;
import com.metroticket_api.rbac.RbacDtos.RoleView;
import com.metroticket_api.rbac.RbacDtos.UpdateRolePermissionsRequest;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAuthority('RBAC_MANAGE')")
public class AdminRoleController {

    private final RbacService rbac;

    public AdminRoleController(RbacService rbac) {
        this.rbac = rbac;
    }

    @GetMapping("/roles")
    public List<RoleView> roles() {
        return rbac.listRoles();
    }

    @GetMapping("/permissions")
    public List<PermissionView> permissions() {
        return rbac.listPermissions();
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleView create(@Valid @RequestBody CreateRoleRequest req) {
        return rbac.createRole(req.code(), req.name(), req.permissions());
    }

    @PutMapping("/roles/{code}/permissions")
    public RoleView updatePermissions(@PathVariable String code, @Valid @RequestBody UpdateRolePermissionsRequest req) {
        return rbac.updatePermissions(code, req.permissions());
    }
}
