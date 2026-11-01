package com.metroticket_api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.metroticket_api.TestcontainersConfiguration;

/** Dùng tài khoản mẫu của DemoDataSeeder nên đồng thời kiểm tra luôn seeder. */
@SpringBootTest(properties = { "app.seed.demo-data=true", "app.seed.file=classpath:seed/test-users.json" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class NotificationIntegrationTest {

    private static final String PASSWORD = "Demo@12345";
    private static final String ADMIN = "admin.demo@metroticket.test";
    private static final String STAFF = "staff.demo@metroticket.test";
    private static final String PASSENGER = "passenger.demo@metroticket.test";

    @Autowired
    MockMvc mvc;

    @Test
    void demoAccountsCoverEveryLoginOutcome() throws Exception {
        for (String email : new String[] { ADMIN, STAFF, PASSENGER }) {
            call(post("/api/auth/login"), null, credentials(email, PASSWORD)).andExpect(status().isOk());
        }
        call(post("/api/auth/login"), null, credentials("pending.demo@metroticket.test", PASSWORD))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Email chưa được xác thực"));
        call(post("/api/auth/login"), null, credentials("locked.demo@metroticket.test", PASSWORD))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Tài khoản đã bị khoá"));
        // Quyền mới được cấp cho ADMIN qua migration
        call(get("/api/me"), token(ADMIN), null).andExpect(jsonPath("$.permissions", hasItem("NOTIFICATION_MANAGE")));
    }

    @Test
    void userManagesOwnNotificationsOnly() throws Exception {
        String staff = token(STAFF);
        String other = token(PASSENGER);

        // Thông báo mẫu có sẵn. Đếm tương đối vì test gửi cho tất cả có thể đã chạy trước
        int unread = JsonPath.read(call(get("/api/me/notifications/unread-count"), staff, null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.count");
        assertThat(unread).isGreaterThanOrEqualTo(2);
        String page = call(get("/api/me/notifications?size=100"), staff, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].title", hasItem("Lịch bảo trì hệ thống")))
                .andExpect(jsonPath("$.content[?(@.title=='Chào mừng đến với MetroTicket')].read").value(hasItem(true)))
                .andReturn().getResponse().getContentAsString();
        int total = JsonPath.read(page, "$.page.totalElements");
        call(get("/api/me/notifications?unreadOnly=true"), staff, null).andExpect(jsonPath("$.page.totalElements").value(unread));

        String unreadId = JsonPath.read(call(get("/api/me/notifications?unreadOnly=true"), staff, null)
                .andReturn().getResponse().getContentAsString(), "$.content[0].id");
        assertThat(page).contains(unreadId);

        // Người khác không đọc hay xoá được
        call(patch("/api/me/notifications/" + unreadId + "/read"), other, null).andExpect(status().isNotFound());
        call(delete("/api/me/notifications/" + unreadId), other, null).andExpect(status().isNotFound());
        call(patch("/api/me/notifications/" + UUID.randomUUID() + "/read"), staff, null).andExpect(status().isNotFound());

        // Đánh dấu đã đọc hai lần vẫn thành công
        call(patch("/api/me/notifications/" + unreadId + "/read"), staff, null).andExpect(status().isNoContent());
        call(patch("/api/me/notifications/" + unreadId + "/read"), staff, null).andExpect(status().isNoContent());
        call(get("/api/me/notifications/unread-count"), staff, null).andExpect(jsonPath("$.count").value(unread - 1));

        call(post("/api/me/notifications/read-all"), staff, null).andExpect(status().isNoContent());
        call(get("/api/me/notifications/unread-count"), staff, null).andExpect(jsonPath("$.count").value(0));

        call(delete("/api/me/notifications/" + unreadId), staff, null).andExpect(status().isNoContent());
        call(get("/api/me/notifications"), staff, null).andExpect(jsonPath("$.page.totalElements").value(total - 1));
        call(get("/api/me/notifications"), null, null).andExpect(status().isUnauthorized());
    }

    @Test
    void adminSendsNotifications() throws Exception {
        String admin = token(ADMIN);
        String passenger = token(PASSENGER);
        String passengerId = JsonPath.read(call(get("/api/me"), passenger, null).andReturn().getResponse().getContentAsString(), "$.id");

        // Không có quyền
        call(post("/api/admin/notifications"), passenger, "{\"all\":true,\"title\":\"T\",\"body\":\"B\"}").andExpect(status().isForbidden());

        // Phải chọn đúng một kiểu người nhận
        call(post("/api/admin/notifications"), admin, "{\"title\":\"T\",\"body\":\"B\"}").andExpect(status().isBadRequest());
        call(post("/api/admin/notifications"), admin, "{\"all\":true,\"userIds\":[\"%s\"],\"title\":\"T\",\"body\":\"B\"}".formatted(passengerId))
                .andExpect(status().isBadRequest());
        call(post("/api/admin/notifications"), admin, "{\"userIds\":[\"%s\"],\"title\":\"T\",\"body\":\"B\"}".formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest());
        call(post("/api/admin/notifications"), admin, "{\"all\":true,\"title\":\" \",\"body\":\"B\"}").andExpect(status().isBadRequest());

        call(post("/api/admin/notifications"), admin, "{\"userIds\":[\"%s\"],\"title\":\"Riêng\",\"body\":\"Nội dung\"}".formatted(passengerId))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.recipients").value(1));
        call(get("/api/me/notifications"), passenger, null)
                .andExpect(jsonPath("$.content[0].title").value("Riêng"))
                .andExpect(jsonPath("$.content[0].type").value("ANNOUNCEMENT"))
                .andExpect(jsonPath("$.content[0].read").value(false));

        // Gửi tất cả: chỉ tài khoản ACTIVE
        String all = call(post("/api/admin/notifications"), admin, "{\"all\":true,\"title\":\"Chung\",\"body\":\"Nội dung\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int recipients = JsonPath.read(all, "$.recipients");
        assertThat(recipients).isGreaterThanOrEqualTo(3);
        call(get("/api/me/notifications"), passenger, null).andExpect(jsonPath("$.content[0].title").value("Chung"));
    }

    @Test
    void authActionsCreateEventsAndNotifications() throws Exception {
        // Tạo user riêng vì test này đổi mật khẩu
        String admin = token(ADMIN);
        String email = "events@example.com";
        String created = call(post("/api/admin/users"), admin,
                "{\"email\":\"%s\",\"fullName\":\"E\",\"roles\":[\"PASSENGER\"],\"temporaryPassword\":\"%s\"}".formatted(email, PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lastLoginAt").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        call(post("/api/auth/login"), null, credentials(email, "sai-mat-khau")).andExpect(status().isUnauthorized());
        String access = token(email);
        call(get("/api/me"), access, null).andExpect(jsonPath("$.lastLoginAt").isNotEmpty());
        call(put("/api/me/password"), access, "{\"currentPassword\":\"%s\",\"newPassword\":\"MatKhauMoi@456\"}".formatted(PASSWORD))
                .andExpect(status().isNoContent());
        access = JsonPath.read(call(post("/api/auth/login"), null, credentials(email, "MatKhauMoi@456"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");

        // Mới nhất trước; lần sai vẫn được lưu dù request trả lỗi
        call(get("/api/me/auth-events"), access, null)
                .andExpect(jsonPath("$.content[0].type").value("LOGIN_SUCCESS"))
                .andExpect(jsonPath("$.content[1].type").value("PASSWORD_CHANGED"))
                .andExpect(jsonPath("$.content[2].type").value("LOGIN_SUCCESS"))
                .andExpect(jsonPath("$.content[3].type").value("LOGIN_FAILED"))
                .andExpect(jsonPath("$.content[4].type").value("ACCOUNT_CREATED_BY_ADMIN"))
                .andExpect(jsonPath("$.content[0].ipAddress").isNotEmpty())
                // Không lộ admin nào thao tác, và không có IP của admin
                .andExpect(jsonPath("$.content[4].ipAddress").isEmpty())
                .andExpect(jsonPath("$.content[4].actorId").doesNotExist());

        String adminId = JsonPath.read(call(get("/api/me"), admin, null).andReturn().getResponse().getContentAsString(), "$.id");
        call(get("/api/admin/users/" + id + "/auth-events"), admin, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[4].actorId").value(adminId));
        call(get("/api/admin/users/" + UUID.randomUUID() + "/auth-events"), admin, null).andExpect(status().isNotFound());
        call(get("/api/admin/users/" + id + "/auth-events"), access, null).andExpect(status().isForbidden());

        call(get("/api/me/notifications"), access, null)
                .andExpect(jsonPath("$.content[0].type").value("SECURITY"))
                .andExpect(jsonPath("$.content[0].title").value("Mật khẩu đã được đổi"))
                .andExpect(jsonPath("$.content[1].title").value("Chào mừng đến với MetroTicket"));
    }

    private String token(String email) throws Exception {
        return JsonPath.read(call(post("/api/auth/login"), null, credentials(email, PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    private static String credentials(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }
}
