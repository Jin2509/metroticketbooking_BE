package com.metroticket_api.rbac;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

/**
 * Vai trò = tập hợp quyền (role_permission). Người dùng nhận quyền thông qua vai trò (user_role).
 */
@Entity
@Table(name = "role")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    /** Vai trò hệ thống (seed sẵn) không được xoá hay đổi mã. */
    @Column(name = "is_system", nullable = false)
    private boolean system;

    @ManyToMany
    @JoinTable(name = "role_permission",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new HashSet<>();

    protected Role() {
        // cho JPA
    }

    public Role(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public void replacePermissions(Set<Permission> newPermissions) {
        permissions.clear();
        permissions.addAll(newPermissions);
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public boolean isSystem() { return system; }
    public Set<Permission> getPermissions() { return permissions; }
}
