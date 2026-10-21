package com.metroticket_api.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    /** Nạp user kèm roles trong một câu JOIN (dùng khi đăng nhập để ghi roles vào JWT). */
    @EntityGraph(attributePaths = "roles")
    Optional<User> findWithRolesByEmail(String email);

    @EntityGraph(attributePaths = "roles")
    Optional<User> findWithRolesById(UUID id);

    boolean existsByEmail(String email);

    boolean existsByRoles_Code(String roleCode);
}
