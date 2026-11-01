package com.metroticket_api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jayway.jsonpath.JsonPath;
import com.metroticket_api.TestcontainersConfiguration;

/** Xác nhận 2 lớp khi đăng nhập và các email thông báo tài khoản. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TwoFactorLoginIntegrationTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("test", "test"));

    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");
    private static final String PASSWORD = "MatKhau@123";
    private static final String CODE_SUBJECT = "Mã xác nhận đăng nhập";

    @Autowired
    MockMvc mvc;

    @Test
    void enablingRequiresPasswordAndSendsNotice() throws Exception {
        String email = "tf.enable@example.com";
        String token = register(email);

        send(put("/api/me/two-factor"), token, "{\"enabled\":true,\"currentPassword\":\"sai-mat-khau\"}")
                .andExpect(status().isBadRequest());
        send(get("/api/me"), token, null).andExpect(jsonPath("$.twoFactorEnabled").value(false));

        send(put("/api/me/two-factor"), token, "{\"enabled\":true,\"currentPassword\":\"%s\"}".formatted(PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.twoFactorEnabled").value(true));
        awaitMail(email, "Đã bật xác nhận 2 lớp", 1);

        // Bật lại khi đang bật: không ghi lịch sử, không gửi thêm mail
        send(put("/api/me/two-factor"), token, "{\"enabled\":true,\"currentPassword\":\"%s\"}".formatted(PASSWORD))
                .andExpect(status().isOk());
        assertThat(eventTypes(token)).containsOnlyOnce("TWO_FACTOR_ENABLED");

        mvc.perform(put("/api/me/two-factor").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"currentPassword\":\"%s\"}".formatted(PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginNeedsCodeBeforeIssuingTokens() throws Exception {
        String email = "tf.login@example.com";
        String token = enabledUser(email);
        int successBefore = (int) eventTypes(token).stream().filter("LOGIN_SUCCESS"::equals).count();

        String ticket = JsonPath.read(login(email)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.twoFactorRequired").value(true))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn().getResponse().getContentAsString(), "$.ticket");
        String code = extractCode(awaitMail(email, CODE_SUBJECT, 1));

        // Chưa qua bước 2 thì chưa tính là đã đăng nhập
        assertThat(eventTypes(token).stream().filter("LOGIN_SUCCESS"::equals).count()).isEqualTo(successBefore);

        verify("ticket-sai", code).andExpect(status().isBadRequest());
        verify(ticket, wrong(code)).andExpect(status().isBadRequest());

        String body = verify(ticket, code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String access = JsonPath.read(body, "$.accessToken");
        send(get("/api/me"), access, null).andExpect(status().isOk());
        assertThat(eventTypes(access)).contains("TWO_FACTOR_FAILED");
        assertThat(eventTypes(access).stream().filter("LOGIN_SUCCESS"::equals).count()).isEqualTo(successBefore + 1);

        // Mã chỉ dùng được một lần
        verify(ticket, code).andExpect(status().isBadRequest());
    }

    @Test
    void wrongPasswordDoesNotSendCode() throws Exception {
        String email = "tf.wrong@example.com";
        enabledUser(email);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"sai-mat-khau\"}".formatted(email)))
                .andExpect(status().isUnauthorized());
        Thread.sleep(300);
        assertThat(mails(email, CODE_SUBJECT)).isEmpty();
    }

    @Test
    void loginAgainDuringCooldownRotatesTicketWithoutNewMail() throws Exception {
        String email = "tf.again@example.com";
        enabledUser(email);
        String first = ticketOf(login(email));
        String code = extractCode(awaitMail(email, CODE_SUBJECT, 1));
        String second = ticketOf(login(email));

        Thread.sleep(300);
        assertThat(mails(email, CODE_SUBJECT)).hasSize(1);
        verify(first, code).andExpect(status().isBadRequest());
        verify(second, code).andExpect(status().isOk());
    }

    @Test
    void resendRespectsCooldown() throws Exception {
        String email = "tf.resend@example.com";
        enabledUser(email);
        String ticket = ticketOf(login(email));
        mvc.perform(post("/api/auth/login/2fa/resend").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticket\":\"%s\"}".formatted(ticket)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        mvc.perform(post("/api/auth/login/2fa/resend").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticket\":\"ticket-sai\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void passwordResetInvalidatesPendingTicketAndSendsNotice() throws Exception {
        String email = "tf.reset@example.com";
        enabledUser(email);
        String ticket = ticketOf(login(email));
        String loginCode = extractCode(awaitMail(email, CODE_SUBJECT, 1));

        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email)))
                .andExpect(status().isAccepted());
        String resetCode = extractCode(awaitMail(email, "đặt lại mật khẩu", 1));
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"code\":\"%s\",\"newPassword\":\"MatKhauMoi@456\"}"
                                .formatted(email, resetCode)))
                .andExpect(status().isNoContent());

        awaitMail(email, "Mật khẩu đã được đặt lại", 1);
        verify(ticket, loginCode)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Phiên xác nhận đã hết hạn, vui lòng đăng nhập lại"));
    }

    @Test
    void repeatedWrongCodesLockLoginEvenWithCorrectPassword() throws Exception {
        String email = "tf.lock@example.com";
        enabledUser(email);
        String ticket = ticketOf(login(email));
        String code = extractCode(awaitMail(email, CODE_SUBJECT, 1));

        for (int i = 0; i < 4; i++) {
            verify(ticket, wrong(code)).andExpect(status().isBadRequest());
        }
        // Lần sai thứ 5 khoá mã: mã đúng cũng không dùng được nữa
        verify(ticket, wrong(code)).andExpect(status().isTooManyRequests());
        verify(ticket, code).andExpect(status().isTooManyRequests());

        // Đăng nhập lại đúng mật khẩu không xoá bộ đếm
        ticket = ticketOf(login(email));
        for (int i = 0; i < 4; i++) {
            verify(ticket, wrong(code)).andExpect(status().isTooManyRequests());
        }
        login(email).andExpect(status().isTooManyRequests());
    }

    @Test
    void disablingReturnsToPasswordOnlyLogin() throws Exception {
        String email = "tf.disable@example.com";
        String token = enabledUser(email);
        send(put("/api/me/two-factor"), token, "{\"enabled\":false,\"currentPassword\":\"%s\"}".formatted(PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.twoFactorEnabled").value(false));
        awaitMail(email, "Đã tắt xác nhận 2 lớp", 1);
        login(email).andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void lockedAccountWithTwoFactorGetsNoCode() throws Exception {
        String email = "tf.locked@example.com";
        String token = enabledUser(email);
        String id = JsonPath.read(send(get("/api/me"), token, null).andReturn().getResponse().getContentAsString(), "$.id");

        String admin = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@metroticket.test\",\"password\":\"Admin@12345\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
        send(patch("/api/admin/users/" + id + "/status"), admin, "{\"status\":\"LOCKED\"}").andExpect(status().isOk());
        awaitMail(email, "Tài khoản đã bị khoá", 1);

        login(email).andExpect(status().isForbidden());
        assertThat(mails(email, CODE_SUBJECT)).isEmpty();

        send(patch("/api/admin/users/" + id + "/status"), admin, "{\"status\":\"ACTIVE\"}").andExpect(status().isOk());
        awaitMail(email, "Tài khoản đã được mở", 1);
    }

    @Test
    void accountEventsAreEmailed() throws Exception {
        String email = "tf.notice@example.com";
        String token = register(email);
        awaitMail(email, "Chào mừng đến với MetroTicket", 1);

        send(put("/api/me/password"), token,
                "{\"currentPassword\":\"%s\",\"newPassword\":\"MatKhauMoi@456\"}".formatted(PASSWORD))
                .andExpect(status().isNoContent());
        MimeMessage notice = awaitMail(email, "Mật khẩu đã được đổi", 1);
        assertThat(GreenMailUtil.getBody(notice)).doesNotContain("MatKhauMoi");
    }

    /** Đăng ký, xác thực, trả về access token. */
    private String register(String email) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\",\"fullName\":\"Người Dùng\"}".formatted(email, PASSWORD)))
                .andExpect(status().isAccepted());
        String code = extractCode(awaitMail(email, "Mã xác thực email", 1));
        mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code)))
                .andExpect(status().isNoContent());
        return JsonPath.read(login(email).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.accessToken");
    }

    private String enabledUser(String email) throws Exception {
        String token = register(email);
        send(put("/api/me/two-factor"), token, "{\"enabled\":true,\"currentPassword\":\"%s\"}".formatted(PASSWORD))
                .andExpect(status().isOk());
        return token;
    }

    private ResultActions login(String email) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)));
    }

    private ResultActions verify(String ticket, String code) throws Exception {
        return mvc.perform(post("/api/auth/login/2fa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticket\":\"%s\",\"code\":\"%s\"}".formatted(ticket, code)));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private List<String> eventTypes(String token) throws Exception {
        return JsonPath.read(send(get("/api/me/auth-events?size=100"), token, null)
                .andReturn().getResponse().getContentAsString(), "$.content[*].type");
    }

    private static String ticketOf(ResultActions login) throws Exception {
        return JsonPath.read(login.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(), "$.ticket");
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    private List<MimeMessage> mails(String to, String subject) throws Exception {
        List<MimeMessage> matched = new ArrayList<>();
        for (MimeMessage mail : greenMail.getReceivedMessages()) {
            if (mail.getAllRecipients()[0].toString().equals(to) && mail.getSubject().contains(subject)) {
                matched.add(mail);
            }
        }
        return matched;
    }

    private MimeMessage awaitMail(String to, String subject, int count) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (true) {
            List<MimeMessage> matched = mails(to, subject);
            if (matched.size() >= count) {
                return matched.get(count - 1);
            }
            assertThat(System.currentTimeMillis()).as("chờ mail '%s' tới %s", subject, to).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private static String extractCode(MimeMessage mail) {
        Matcher m = CODE.matcher(GreenMailUtil.getBody(mail));
        assertThat(m.find()).as("mail phải chứa mã 6 số").isTrue();
        return m.group(1);
    }
}
