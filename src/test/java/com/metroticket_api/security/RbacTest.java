package com.metroticket_api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.metroticket_api.TestcontainersConfiguration;
import com.metroticket_api.rbac.Permission;
import com.metroticket_api.rbac.PermissionCodes;
import com.metroticket_api.rbac.PermissionRepository;

/** Kiểm tra RBAC bằng JWT giả lập: quyền được tra qua RolePermissionCache thật (dữ liệu seed từ Flyway). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RbacTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    RoleAuthoritiesConverter converter;

    @Autowired
    PermissionRepository permissions;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private RequestPostProcessor as(String... roles) {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString()).claim(TokenService.ROLES_CLAIM, List.of(roles)))
                .authorities(converter);
    }

    @Test
    void anonymousGets401() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void permissionMatrix() throws Exception {
        mvc.perform(get("/api/admin/users").with(as("PASSENGER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").with(as("STAFF"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").with(as("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/roles").with(as("STAFF"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/roles").with(as("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='STAFF')].permissions[*]").value(
                        org.hamcrest.Matchers.containsInAnyOrder("TICKET_VALIDATE", "TICKET_VIEW_ALL", "TICKET_VIEW_OWN")));
    }

    @Test
    void unknownRoleHasNoAccess() throws Exception {
        mvc.perform(get("/api/admin/users").with(as("HACKER"))).andExpect(status().isForbidden());
    }

    @Test
    void newRoleWorksImmediatelyAndPermissionChangesApplyWithoutNewToken() throws Exception {
        // Admin tạo vai trò AUDITOR chỉ có quyền xem danh sách user
        mvc.perform(post("/api/admin/roles").with(as("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"AUDITOR\",\"name\":\"Kiểm toán\",\"permissions\":[\"USER_VIEW\"]}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/admin/users").with(as("AUDITOR"))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/roles").with(as("AUDITOR"))).andExpect(status().isForbidden());

        // Thu hồi quyền → cùng "token" đó bị chặn ngay
        mvc.perform(put("/api/admin/roles/AUDITOR/permissions").with(as("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").with(as("AUDITOR"))).andExpect(status().isForbidden());
    }

    @Test
    void adminRoleCannotBeStripped() throws Exception {
        mvc.perform(put("/api/admin/roles/ADMIN/permissions").with(as("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownPermissionRejected() throws Exception {
        mvc.perform(post("/api/admin/roles").with(as("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"BAD\",\"name\":\"x\",\"permissions\":[\"FLY\"]}"))
                .andExpect(status().isBadRequest());
    }

    /** Lưới an toàn: quên @PreAuthorize ở endpoint admin thì test fail. */
    @Test
    void everyAdminEndpointRequiresAPermission() {
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            boolean admin = info.getPatternValues().stream().anyMatch(p -> p.startsWith("/api/admin"));
            if (admin) {
                boolean secured = AnnotatedElementUtils.hasAnnotation(method.getMethod(), PreAuthorize.class)
                        || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), PreAuthorize.class);
                assertThat(secured).as("%s thiếu @PreAuthorize", info).isTrue();
            }
        });
    }

    /** Mọi hằng số trong PermissionCodes phải tồn tại trong bảng permission. */
    @Test
    void permissionCodesAreSeeded() {
        Set<String> constants = Arrays.stream(PermissionCodes.class.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                .map(RbacTest::value)
                .collect(Collectors.toSet());
        Set<String> seeded = permissions.findAll().stream().map(Permission::getCode).collect(Collectors.toSet());
        assertThat(seeded).containsAll(constants);
    }

    private static String value(Field f) {
        try {
            return (String) f.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
