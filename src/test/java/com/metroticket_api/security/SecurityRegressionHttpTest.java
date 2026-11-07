package com.metroticket_api.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.metroticket_api.TestcontainersConfiguration;
import com.metroticket_api.user.User;
import com.metroticket_api.user.UserRepository;
import com.metroticket_api.user.UserStatus;

/** Hồi quy cho các lỗ hổng đã sửa ở đợt rà soát bảo mật (xem report.md). */
@SpringBootTest(properties = {
        "app.payment.sepay.enabled=true",
        "app.payment.sepay.bank=MBBank",
        "app.payment.sepay.account-number=0123456789",
        "app.payment.sepay.webhook-api-key=http-test-key" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SecurityRegressionHttpTest {

    @Autowired MockMvc mvc;
    @Autowired RoleAuthoritiesConverter converter;
    @Autowired UserRepository users;

    private RequestPostProcessor as(UUID subject, String... roles) {
        return jwt().jwt(j -> j.subject(subject.toString()).claim(TokenService.ROLES_CLAIM, List.of(roles)))
                .authorities(converter);
    }

    private UUID realUser() {
        return users.save(new User("s" + UUID.randomUUID() + "@test.vn", "{noop}x", "Test", UserStatus.ACTIVE,
                java.time.Instant.now())).getId();
    }

    private static final String WEBHOOK = "/api/v1/payments/sepay/webhook";
    private static final String BODY = """
            {"id": 987654321, "gateway": "MBBank", "transactionDate": "2026-11-07 10:00:00",
             "accountNumber": "0123456789", "code": null, "content": "MTQABCDEFGH", "transferType": "in",
             "transferAmount": 7000, "accumulated": 0, "referenceCode": "FT1", "description": ""}""";

    @Test
    void webhookRequiresSepayApiKey() throws Exception {
        mvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(WEBHOOK).header("Authorization", "Apikey wrong").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        // JWT hợp lệ không thay được API key của SePay
        mvc.perform(post(WEBHOOK).with(as(UUID.randomUUID(), "ADMIN")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(WEBHOOK).header("Authorization", "Apikey http-test-key").contentType(MediaType.APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(WEBHOOK).header("Authorization", "Apikey http-test-key").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void announcementsRequirePermissionAndDraftsStayPrivate() throws Exception {
        String body = "{\"title\":\"Tạm dừng chạy tàu\",\"content\":\"Bảo trì đoạn Ba Son\",\"scope\":\"ALL\"}";
        mvc.perform(post("/api/v1/announcements").with(as(UUID.randomUUID(), "PASSENGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        UUID staff = realUser();
        String created = mvc.perform(post("/api/v1/announcements").with(as(staff, "STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value(staff.toString()))
                .andReturn().getResponse().getContentAsString();
        String id = created.replaceAll("(?s).*?\"id\":(\\d+).*", "$1");

        mvc.perform(get("/api/v1/announcements/" + id).with(as(UUID.randomUUID(), "PASSENGER")))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/announcements/" + id + "/submit").with(as(staff, "STAFF"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/announcements/" + id + "/approve").with(as(staff, "STAFF")))
                .andExpect(status().isForbidden());
        UUID manager = realUser();
        mvc.perform(post("/api/v1/announcements/" + id + "/approve").with(as(manager, "MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedBy").value(manager.toString()));

        mvc.perform(get("/api/v1/announcements/" + id).with(as(UUID.randomUUID(), "PASSENGER")))
                .andExpect(status().isOk());
    }

    @Test
    void externalApiParametersAreBounded() throws Exception {
        mvc.perform(get("/api/v1/bus/stops/nearby").param("lat", "10.77").param("lng", "106.69").param("limit", "-1")
                        .with(as(UUID.randomUUID(), "PASSENGER")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/bus/stops/nearby").param("lat", "10.77").param("lng", "106.69").param("radiusKm", "1000")
                        .with(as(UUID.randomUUID(), "PASSENGER")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stations/1/facilities").param("radiusMeters", "1000000")
                        .with(as(UUID.randomUUID(), "PASSENGER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ticketProductManagementRbac() throws Exception {
        String body = "{\"code\":\"WEEK_3\",\"name\":\"Vé 3 tuần\",\"durationUnit\":\"WEEK\",\"durationValue\":3,"
                + "\"priceVnd\":400000,\"status\":\"INACTIVE\"}";
        mvc.perform(post("/api/v1/ticket-products").with(as(UUID.randomUUID(), "PASSENGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ticket-products").with(as(UUID.randomUUID(), "MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        // Manager xem được toàn bộ (kể cả loại ngừng bán) để báo cáo, nhưng không sửa được
        mvc.perform(get("/api/v1/ticket-products").with(as(UUID.randomUUID(), "MANAGER"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/ticket-products").with(as(UUID.randomUUID(), "PASSENGER"))).andExpect(status().isForbidden());

        UUID admin = realUser();
        String created = mvc.perform(post("/api/v1/ticket-products").with(as(admin, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value(admin.toString()))
                .andReturn().getResponse().getContentAsString();
        String id = created.replaceAll("(?s).*?\"id\":(\\d+).*", "$1");
        // sửa mà thiếu version → 400
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/ticket-products/" + id)
                        .with(as(admin, "ADMIN")).contentType(MediaType.APPLICATION_JSON).content("{\"priceVnd\":1}"))
                .andExpect(status().isBadRequest());
        // vé ngày nhiều kỳ bị từ chối
        mvc.perform(post("/api/v1/ticket-products").with(as(admin, "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"DAY_5\",\"name\":\"x\",\"durationUnit\":\"DAY\",\"durationValue\":5,"
                                + "\"priceVnd\":1000,\"maxPeriods\":2}"))
                .andExpect(status().isBadRequest());
        // loại vé ngừng bán không lộ ra danh sách công khai
        mvc.perform(get("/api/v1/public/ticket-products"))
                .andExpect(jsonPath("$[?(@.code=='WEEK_3')]").isEmpty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/ticket-products/" + id)
                        .with(as(admin, "ADMIN")))
                .andExpect(status().isNoContent());
    }

    @Test
    void studentVerificationAndGreenJourneyRbac() throws Exception {
        // hàng chờ duyệt + ảnh thẻ (dữ liệu cá nhân): chỉ ADMIN/MANAGER
        mvc.perform(get("/api/v1/admin/student-verifications").with(as(UUID.randomUUID(), "PASSENGER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/student-verifications").with(as(UUID.randomUUID(), "STAFF")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/student-verifications").param("status", "NEEDS_REVIEW")
                        .with(as(UUID.randomUUID(), "MANAGER")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/student-verifications/" + UUID.randomUUID() + "/image")
                        .with(as(UUID.randomUUID(), "PASSENGER")))
                .andExpect(status().isForbidden());
        // điều kiện xác thực: MANAGER xem được, chỉ ADMIN sửa
        mvc.perform(get("/api/v1/admin/student-policy").with(as(UUID.randomUUID(), "MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountPercent").value(50))
                .andExpect(jsonPath("$.aiConfigured").value(false));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/student-policy")
                        .with(as(UUID.randomUUID(), "MANAGER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"discountPercent\":90}"))
                .andExpect(status().isForbidden());
        // nộp hồ sơ: phải đồng ý xử lý dữ liệu
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/me/student-verifications")
                        .file("image", new byte[] { 1 }).param("fullName", "A").param("studentCode", "B").param("school", "C")
                        .with(as(UUID.randomUUID(), "PASSENGER")))
                .andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/me/student-verifications")
                        .file("image", new byte[] { 1 }).param("fullName", "A").param("studentCode", "B").param("school", "C")
                        .param("consent", "true").with(as(UUID.randomUUID(), "STAFF")))
                .andExpect(status().isForbidden()); // STAFF không có TICKET_BUY

        // Hành trình xanh
        mvc.perform(get("/api/v1/public/green-journey/stats")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/green-journey/leaderboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/green-journey/leaderboard").param("month", "2026-13")
                        .with(as(UUID.randomUUID(), "PASSENGER")))
                .andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/admin/green-journey/settings")
                        .with(as(UUID.randomUUID(), "MANAGER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"pointsPerKm\":1000}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void notificationCampaignsDevicesAndStreamRbac() throws Exception {
        String body = "{\"title\":\"Bảo trì\",\"body\":\"23:00\",\"type\":\"SYSTEM\",\"audienceType\":\"ALL\"}";
        String base = "/api/v1/admin/notification-campaigns";
        mvc.perform(post(base).with(as(UUID.randomUUID(), "PASSENGER")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(base).with(as(UUID.randomUUID(), "MANAGER")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(get(base).with(as(UUID.randomUUID(), "MANAGER"))).andExpect(status().isOk());
        mvc.perform(get(base).with(as(UUID.randomUUID(), "STAFF"))).andExpect(status().isForbidden());
        UUID admin = realUser();
        mvc.perform(post(base).with(as(admin, "ADMIN")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.createdBy").value(admin.toString()));
        // link ra ngoài (chống lừa đảo) bị từ chối
        mvc.perform(post(base).with(as(admin, "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"body\":\"y\",\"type\":\"PROMOTION\",\"audienceType\":\"ALL\","
                                + "\"link\":\"https://evil.example/login\"}"))
                .andExpect(status().isBadRequest());

        // thiết bị & tuỳ chọn của chính mình
        UUID passenger = realUser();
        mvc.perform(post("/api/me/devices").with(as(passenger, "PASSENGER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"fcm-token-xyz\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/me/notification-preferences")
                        .with(as(passenger, "PASSENGER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"promotionEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promotionEnabled").value(false))
                .andExpect(jsonPath("$.pushEnabled").value(true));

        // realtime SSE
        mvc.perform(get("/api/me/notifications/stream")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/notifications/stream").with(as(passenger, "PASSENGER")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted());
    }

    @Test
    void bannerUploadUsesPermissionNotRoleName() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/content/banners")
                        .file("image", new byte[] { 1 }).param("title", "x").with(as(UUID.randomUUID(), "MANAGER")))
                .andExpect(status().isForbidden());
    }
}
