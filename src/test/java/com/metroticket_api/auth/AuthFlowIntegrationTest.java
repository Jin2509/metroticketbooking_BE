package com.metroticket_api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jayway.jsonpath.JsonPath;
import com.metroticket_api.TestcontainersConfiguration;

/** Luồng đầy đủ: đăng ký → nhận mail → xác thực → đăng nhập → gọi API theo quyền. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuthFlowIntegrationTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("test", "test"));

    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired
    MockMvc mvc;

    @Test
    void registerVerifyLoginAndAccessByRole() throws Exception {
        String email = "an.nguyen@example.com";

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"MatKhau@123","fullName":"Nguyễn An"}
                                """.formatted(email)))
                .andExpect(status().isAccepted());

        // Mail gửi bất đồng bộ sau commit → chờ tối đa 5 giây
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage mail = greenMail.getReceivedMessages()[0];
        assertThat(mail.getAllRecipients()[0].toString()).isEqualTo(email);
        assertThat(mail.getSubject()).contains("Mã xác thực");
        String code = extractCode(GreenMailUtil.getBody(mail));

        // Chưa xác thực thì không đăng nhập được
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email)))
                .andExpect(status().isForbidden());

        // Gửi lại ngay lập tức → 429 do cooldown
        mvc.perform(post("/api/auth/resend-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code)))
                .andExpect(status().isNoContent());

        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.accessToken");

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roles[0]").value("PASSENGER"))
                .andExpect(jsonPath("$.permissions").isArray());

        // PASSENGER không vào được khu vực admin
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongCodeIsLimited() throws Exception {
        String email = "binh.tran@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"MatKhau@123","fullName":"Trần Bình"}
                                """.formatted(email)))
                .andExpect(status().isAccepted());

        String wrong = "{\"email\":\"%s\",\"code\":\"000000\"}".formatted(email);
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content(wrong))
                    .andExpect(status().isBadRequest());
        }
        // Lần sai thứ 5 (max-failed-attempts=5) → khoá mã
        mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content(wrong))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void adminPromotesUserToStaffWithRealTokens() throws Exception {
        String email = "chi.le@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"MatKhau@123","fullName":"Lê Chi"}
                                """.formatted(email)))
                .andExpect(status().isAccepted());
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        String code = extractCode(GreenMailUtil.getBody(greenMail.getReceivedMessages()[0]));
        mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code)))
                .andExpect(status().isNoContent());

        String userToken = login(email, "MatKhau@123");
        String userId = JsonPath.read(mvc.perform(get("/api/me").header("Authorization", "Bearer " + userToken))
                .andReturn().getResponse().getContentAsString(), "$.id");

        // Admin được tạo bởi AdminBootstrap
        String adminToken = login("admin@metroticket.test", "Admin@12345");
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mvc.perform(put("/api/admin/users/" + userId + "/roles").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"STAFF\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("STAFF"));

        // Đăng nhập lại → token mới mang vai trò STAFF
        String staffToken = login(email, "MatKhau@123");
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + staffToken))
                .andExpect(jsonPath("$.roles[0]").value("STAFF"))
                .andExpect(jsonPath("$.permissions").value(
                        org.hamcrest.Matchers.containsInAnyOrder("TICKET_VALIDATE", "TICKET_VIEW_ALL", "TICKET_VIEW_OWN")));

        // Sai mật khẩu và email không tồn tại trả cùng một lỗi
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"khong-co@example.com\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    private String login(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static String loginJson(String email) {
        return "{\"email\":\"%s\",\"password\":\"MatKhau@123\"}".formatted(email);
    }

    private static String extractCode(String body) {
        Matcher m = CODE.matcher(body);
        assertThat(m.find()).as("mail phải chứa mã 6 số").isTrue();
        return m.group(1);
    }
}
