package com.metroticket_api.user;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import com.metroticket_api.rbac.Role;

@Entity
@Table(name = "app_user") // "user" là từ khoá trong PostgreSQL
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @Column(name = "two_factor_enabled", nullable = false)
    private boolean twoFactorEnabled;

    @ManyToMany
    @JoinTable(name = "user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    protected User() {
        // cho JPA
    }

    public User(String email, String passwordHash, String fullName, UserStatus status, Instant now) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.status = status;
        this.createdAt = now;
    }

    public void activate(Instant now) {
        if (status == UserStatus.PENDING_VERIFICATION) {
            status = UserStatus.ACTIVE;
            emailVerifiedAt = now;
        }
    }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }

    public void changeEmail(String newEmail) {
        this.email = newEmail;
    }

    public void changeFullName(String fullName) {
        this.fullName = fullName;
    }

    /** Đăng ký lại khi chưa xác thực email: ghi đè mật khẩu và họ tên. */
    public void updatePendingRegistration(String newHash, String fullName) {
        if (status != UserStatus.PENDING_VERIFICATION) {
            throw new IllegalStateException("Chỉ cập nhật được tài khoản chưa xác thực");
        }
        this.passwordHash = newHash;
        this.fullName = fullName;
    }

    public void assignRole(Role role) { roles.add(role); }

    public void replaceRoles(Collection<Role> newRoles) {
        roles.clear();
        roles.addAll(newRoles);
    }

    public void changePassword(String newHash, Instant now) {
        this.passwordHash = newHash;
        this.passwordChangedAt = now;
    }


    public void changeStatus(UserStatus status) { this.status = status; }

    public void changeTwoFactor(boolean enabled) { this.twoFactorEnabled = enabled; }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getFullName() { return fullName; }
    public UserStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getPasswordChangedAt() { return passwordChangedAt; }
    public boolean isTwoFactorEnabled() { return twoFactorEnabled; }
    public Set<Role> getRoles() { return roles; }
}
