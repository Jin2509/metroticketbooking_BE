package com.metroticket_api.rbac;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByCode(String code);

    List<Role> findByCodeIn(Collection<String> codes);

    boolean existsByCode(String code);

    @EntityGraph(attributePaths = "permissions")
    @Query("select r from Role r order by r.code")
    List<Role> findAllWithPermissions();

    /** Toàn bộ ánh xạ vai trò → quyền trong MỘT câu truy vấn, dùng để nạp cache. */
    @Query("""
            select new com.metroticket_api.rbac.RolePermissionRow(r.code, p.code)
            from Role r left join r.permissions p
            """)
    List<RolePermissionRow> findAllRolePermissionRows();
}
