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

    @Test
    void refreshRotatesAndLogoutRevokesTokens() throws Exception {
        String email = "dung.pham@example.com";
        registerAndVerify(email, 0);

        String first = loginBody(email, "MatKhau@123");
        String refresh1 = JsonPath.read(first, "$.refreshToken");

        // Refresh → cặp token mới, refresh token cũ bị thu hồi
        String second = mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(refresh1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String refresh2 = JsonPath.read(second, "$.refreshToken");
        assertThat(refresh2).isNotEqualTo(refresh1);

        // Dùng lại token cũ → 401 và cả phiên bị thu hồi (refresh2 cũng chết)
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshJson(refresh1)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshJson(refresh2)))
                .andExpect(status().isUnauthorized());

        // Đăng nhập lại rồi đăng xuất kèm access token
        String third = loginBody(email, "MatKhau@123");
        String access = JsonPath.read(third, "$.accessToken");
        String refresh3 = JsonPath.read(third, "$.refreshToken");
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON).content(refreshJson(refresh3)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshJson(refresh3)))
                .andExpect(status().isUnauthorized());

        // Đăng xuất lần nữa (token đã thu hồi) vẫn 204
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON).content(refreshJson(refresh3)))
                .andExpect(status().isNoContent());
    }

    @Test
    void forgotPasswordResetsAndSignsOutEverywhere() throws Exception {
        String email = "em.vo@example.com";
        registerAndVerify(email, 0);
        String oldRefresh = JsonPath.read(loginBody(email, "MatKhau@123"), "$.refreshToken");

        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andExpect(status().isAccepted());
        // Email không tồn tại cũng trả 202 (không lộ tài khoản)
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"khong-co@example.com\"}"))
                .andExpect(status().isAccepted());

        assertThat(greenMail.waitForIncomingEmail(5000, 2)).isTrue();
        MimeMessage mail = greenMail.getReceivedMessages()[1];
        assertThat(mail.getSubject()).contains("đặt lại mật khẩu");
        String code = extractCode(GreenMailUtil.getBody(mail));

        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"code\":\"%s\",\"newPassword\":\"MatKhauMoi@456\"}"
                                .formatted(email, code)))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginJson(email)))
                .andExpect(status().isUnauthorized());
        login(email, "MatKhauMoi@456");
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshJson(oldRefresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unverifiedEmailCanRegisterAgain() throws Exception {
        String email = "giang.do@example.com";
        String json = """
                {"email":"%s","password":"MatKhau@123","fullName":"Đỗ Giang"}
                """.formatted(email);
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isAccepted());
        // Đăng ký lại ngay → vướng cooldown gửi mã
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isTooManyRequests());
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        String code = extractCode(GreenMailUtil.getBody(greenMail.getReceivedMessages()[0]));
        mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code)))
                .andExpect(status().isNoContent());
        // Đã xác thực → đăng ký lại bị 409
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isConflict());
    }

    private void registerAndVerify(String email, int mailIndex) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"MatKhau@123","fullName":"Người Dùng"}
                                """.formatted(email)))
                .andExpect(status().isAccepted());
        assertThat(greenMail.waitForIncomingEmail(5000, mailIndex + 1)).isTrue();
        String code = extractCode(GreenMailUtil.getBody(greenMail.getReceivedMessages()[mailIndex]));
        mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code)))
                .andExpect(status().isNoContent());
    }

    private String loginBody(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static String refreshJson(String refreshToken) {
        return "{\"refreshToken\":\"%s\"}".formatted(refreshToken);
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
