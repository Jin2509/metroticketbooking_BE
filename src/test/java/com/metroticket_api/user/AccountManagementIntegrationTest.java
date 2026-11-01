package com.metroticket_api.user;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jayway.jsonpath.JsonPath;
import com.metroticket_api.TestcontainersConfiguration;

/** Hồ sơ cá nhân, admin quản lý người dùng và các tình huống tấn công, dùng token thật. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AccountManagementIntegrationTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("test", "test"));

    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");
    private static final String PASSWORD = "MatKhau@123";
    private static final String ADMIN_EMAIL = "admin@metroticket.test";
    private static final String ADMIN_PASSWORD = "Admin@12345";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void changingPasswordRequiresCurrentPasswordAndSignsOut() throws Exception {
        String email = "pw.user@example.com";
        String login = signUpAndLogin(email);
        String access = JsonPath.read(login, "$.accessToken");
        String refresh = JsonPath.read(login, "$.refreshToken");

        call(put("/api/me/password"), access, "{\"newPassword\":\"KeTrom@12345\"}").andExpect(status().isBadRequest());
        call(put("/api/me/password"), access, "{\"currentPassword\":\"sai-mat-khau\",\"newPassword\":\"KeTrom@12345\"}")
                .andExpect(status().isBadRequest());
        call(put("/api/me/password"), access, "{\"currentPassword\":\"%s\",\"newPassword\":\"1\"}".formatted(PASSWORD))
                .andExpect(status().isBadRequest());
        call(post("/api/auth/login"), null, credentials(email, PASSWORD)).andExpect(status().isOk());

        call(put("/api/me/password"), access, "{\"currentPassword\":\"%s\",\"newPassword\":\"MatKhauMoi@456\"}".formatted(PASSWORD))
                .andExpect(status().isNoContent());

        call(get("/api/me"), access, null).andExpect(status().isUnauthorized());
        call(post("/api/auth/refresh"), null, refreshJson(refresh)).andExpect(status().isUnauthorized());
        call(post("/api/auth/login"), null, credentials(email, "MatKhauMoi@456")).andExpect(status().isOk());
    }

    @Test
    void emailChangeCodeIsBoundToRequesterAndNormalized() throws Exception {
        String owner = "owner@example.com";
        String newEmail = "owner.new@example.com";
        String ownerToken = JsonPath.read(signUpAndLogin(owner), "$.accessToken");
        String attackerToken = JsonPath.read(signUpAndLogin("attacker@example.com"), "$.accessToken");

        // Thiếu hoặc sai mật khẩu hiện tại thì không gửi mã
        call(put("/api/me/profile"), ownerToken, "{\"fullName\":\"Owner\",\"email\":\"%s\"}".formatted(newEmail))
                .andExpect(status().isBadRequest());

        call(put("/api/me/profile"), ownerToken, profileJson(newEmail)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(owner));
        String code = extractCode(awaitMail(newEmail, "thay đổi email", 1));

        // Biết mã nhưng không phải người yêu cầu → không lấy được email
        call(post("/api/me/profile/verify-email"), attackerToken, codeJson(newEmail, code)).andExpect(status().isBadRequest());
        // Mã gắn với đúng email đã yêu cầu
        call(post("/api/me/profile/verify-email"), ownerToken, codeJson("khac@example.com", code)).andExpect(status().isBadRequest());

        call(post("/api/me/profile/verify-email"), ownerToken, codeJson(newEmail.toUpperCase(), code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(newEmail));

        call(post("/api/auth/login"), null, credentials(newEmail, PASSWORD)).andExpect(status().isOk());
        call(post("/api/auth/login"), null, credentials(owner, PASSWORD)).andExpect(status().isUnauthorized());
        call(get("/api/me"), ownerToken, null).andExpect(status().isUnauthorized());

        // Email cũ được báo về việc thay đổi
        assertThat(awaitMail(owner, "đã được thay đổi", 1)).isNotNull();
    }

    /** Lỗi cũ: exception làm rollback nên bộ đếm sai không tăng, thử mã được vô hạn lần. */
    @Test
    void wrongEmailChangeCodeIsLimited() throws Exception {
        String newEmail = "limit.new@example.com";
        String token = JsonPath.read(signUpAndLogin("limit@example.com"), "$.accessToken");
        call(put("/api/me/profile"), token, profileJson(newEmail)).andExpect(status().isOk());
        String code = extractCode(awaitMail(newEmail, "thay đổi email", 1));
        String wrong = codeJson(newEmail, code.equals("000000") ? "111111" : "000000");

        for (int i = 0; i < 4; i++) {
            call(post("/api/me/profile/verify-email"), token, wrong).andExpect(status().isBadRequest());
        }
        call(post("/api/me/profile/verify-email"), token, wrong).andExpect(status().isTooManyRequests());
        // Mã đúng cũng không dùng được nữa
        call(post("/api/me/profile/verify-email"), token, codeJson(newEmail, code)).andExpect(status().isTooManyRequests());
    }

    /** Kẻ tấn công không được ghi đè mật khẩu của người đang chờ xác thực khi mã còn hiệu lực. */
    @Test
    void pendingRegistrationCannotBeOverwrittenWhileCodeIsActive() throws Exception {
        String email = "pending@example.com";
        String body = "{\"email\":\"%s\",\"password\":\"%s\",\"fullName\":\"A\"}";
        call(post("/api/auth/register"), null, body.formatted(email, PASSWORD)).andExpect(status().isAccepted());
        awaitMail(email, "xác thực email", 1);

        // Qua cooldown gửi lại nhưng mã vẫn còn hiệu lực
        jdbc.update("update email_verification_challenge set created_at = created_at - interval '2 minutes' where email = ?", email);
        call(post("/api/auth/register"), null, body.formatted(email, "KeTanCong@123"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // Mã đã hết hạn → đăng ký lại được, mật khẩu mới có hiệu lực sau khi xác thực
        jdbc.update("update email_verification_challenge set expires_at = now() - interval '1 second' where email = ?", email);
        call(post("/api/auth/register"), null, body.formatted(email, "MatKhauMoi@456")).andExpect(status().isAccepted());
        String code = extractCode(awaitMail(email, "xác thực email", 2));
        call(post("/api/auth/verify-email"), null, codeJson(email, code)).andExpect(status().isNoContent());
        call(post("/api/auth/login"), null, credentials(email, "MatKhauMoi@456")).andExpect(status().isOk());
    }

    /** BCrypt từ chối mật khẩu trên 72 byte; 72 ký tự có dấu vượt giới hạn này. */
    @Test
    void passwordLongerThan72BytesIsRejected() throws Exception {
        call(post("/api/auth/register"), null,
                "{\"email\":\"long@example.com\",\"password\":\"%s\",\"fullName\":\"A\"}".formatted("ệ".repeat(72)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void loginIsLockedAfterTooManyFailures() throws Exception {
        String email = "brute@example.com";
        signUpAndLogin(email);
        for (int i = 0; i < 10; i++) {
            call(post("/api/auth/login"), null, credentials(email, "sai-" + i)).andExpect(status().isUnauthorized());
        }
        // Khoá cả khi nhập đúng mật khẩu
        call(post("/api/auth/login"), null, credentials(email, PASSWORD)).andExpect(status().isTooManyRequests());
    }

    @Test
    void lockingUserRevokesTokensImmediately() throws Exception {
        String email = "locked@example.com";
        String login = signUpAndLogin(email);
        String access = JsonPath.read(login, "$.accessToken");
        String id = userId(access);
        String admin = adminToken();

        call(patch("/api/admin/users/" + id + "/status"), admin, "{\"status\":\"PENDING_VERIFICATION\"}").andExpect(status().isBadRequest());
        call(patch("/api/admin/users/" + id + "/status"), admin, "{\"status\":\"LOCKED\"}").andExpect(status().isOk());

        call(get("/api/me"), access, null).andExpect(status().isUnauthorized());
        call(post("/api/auth/refresh"), null, refreshJson(JsonPath.read(login, "$.refreshToken"))).andExpect(status().isUnauthorized());
        call(post("/api/auth/login"), null, credentials(email, PASSWORD)).andExpect(status().isForbidden());

        call(patch("/api/admin/users/" + id + "/status"), admin, "{\"status\":\"ACTIVE\"}").andExpect(status().isOk());
        call(post("/api/auth/login"), null, credentials(email, PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void changingRolesInvalidatesOldAccessTokenButRefreshGetsNewRoles() throws Exception {
        String login = signUpAndLogin("promoted@example.com");
        String access = JsonPath.read(login, "$.accessToken");
        String id = userId(access);

        call(put("/api/admin/users/" + id + "/roles"), adminToken(), "{\"roles\":[\"STAFF\"]}").andExpect(status().isOk());

        call(get("/api/me"), access, null).andExpect(status().isUnauthorized());
        String refreshed = call(post("/api/auth/refresh"), null, refreshJson(JsonPath.read(login, "$.refreshToken")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String newAccess = JsonPath.read(refreshed, "$.accessToken");
        call(get("/api/me"), newAccess, null).andExpect(jsonPath("$.roles[0]").value("STAFF"));
    }

    /** Người chỉ có USER_MANAGE không được leo lên ADMIN, nhưng vẫn quản lý được tài khoản thường. */
    @Test
    void userManagerCannotEscalateToAdmin() throws Exception {
        String admin = adminToken();
        String adminId = userId(admin);
        call(post("/api/admin/roles"), admin, "{\"code\":\"HR\",\"name\":\"Nhân sự\",\"permissions\":[\"USER_VIEW\",\"USER_MANAGE\"]}")
                .andExpect(status().isCreated());
        String hrEmail = "hr@example.com";
        String hrId = userId(JsonPath.read(signUpAndLogin(hrEmail), "$.accessToken"));
        call(put("/api/admin/users/" + hrId + "/roles"), admin, "{\"roles\":[\"HR\"]}").andExpect(status().isOk());
        String hr = JsonPath.read(call(post("/api/auth/login"), null, credentials(hrEmail, PASSWORD))
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
        String memberId = userId(JsonPath.read(signUpAndLogin("member@example.com"), "$.accessToken"));

        call(post("/api/admin/users"), hr, "{\"email\":\"evil@example.com\",\"fullName\":\"Evil\",\"roles\":[\"ADMIN\"],\"temporaryPassword\":\"EvilPass@123\"}")
                .andExpect(status().isForbidden());
        call(put("/api/admin/users/" + adminId + "/password"), hr, "{\"newPassword\":\"EvilPass@123\"}").andExpect(status().isForbidden());
        call(patch("/api/admin/users/" + adminId + "/status"), hr, "{\"status\":\"LOCKED\"}").andExpect(status().isForbidden());
        call(put("/api/admin/users/" + memberId + "/roles"), hr, "{\"roles\":[\"ADMIN\"]}").andExpect(status().isForbidden());
        call(put("/api/admin/users/" + memberId + "/roles"), hr, "{\"roles\":[\"HR\"]}").andExpect(status().isForbidden());

        call(put("/api/admin/users/" + memberId + "/roles"), hr, "{\"roles\":[\"STAFF\"]}").andExpect(status().isOk());
        call(patch("/api/admin/users/" + memberId + "/status"), hr, "{\"status\":\"LOCKED\"}").andExpect(status().isOk());
        call(post("/api/auth/login"), null, credentials(ADMIN_EMAIL, ADMIN_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void adminCreatesUserAndCanSearch() throws Exception {
        String admin = adminToken();
        String email = "created.staff@example.com";
        call(post("/api/admin/users"), admin, "{\"email\":\"x@example.com\",\"fullName\":\"X\",\"roles\":[\"STAFF\"],\"temporaryPassword\":\"1\"}")
                .andExpect(status().isBadRequest());
        call(post("/api/admin/users"), admin, "{\"email\":\"x@example.com\",\"fullName\":\"X\",\"roles\":[\"KHONG_CO\"]}")
                .andExpect(status().isBadRequest());

        String created = call(post("/api/admin/users"), admin, "{\"email\":\"%s\",\"fullName\":\"Nhân Viên\",\"roles\":[\"STAFF\"]}".formatted(email.toUpperCase()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        call(post("/api/admin/users"), admin, "{\"email\":\"%s\",\"fullName\":\"N\",\"roles\":[\"STAFF\"]}".formatted(email)).andExpect(status().isConflict());

        // Mail gửi sau commit, chứa mật khẩu tạm đăng nhập được
        String body = (String) awaitMail(email, "đã được tạo", 1).getContent();
        Matcher password = Pattern.compile("Mật khẩu tạm thời: (\\S+)").matcher(body);
        assertThat(password.find()).isTrue();
        call(post("/api/auth/login"), null, credentials(email, password.group(1))).andExpect(status().isOk());

        String id = JsonPath.read(created, "$.id");
        call(get("/api/admin/users/" + id), admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        call(get("/api/admin/users?q=CREATED.staff"), admin, null).andExpect(jsonPath("$.page.totalElements").value(1));
        call(get("/api/admin/users?q=%25"), admin, null).andExpect(jsonPath("$.page.totalElements").value(0));
        call(get("/api/admin/users?status=LOCKED&q=created.staff"), admin, null).andExpect(jsonPath("$.page.totalElements").value(0));
        call(get("/api/admin/users?sort=passwordHash,asc"), admin, null).andExpect(status().isBadRequest());
        call(get("/api/admin/users?status=ABC"), admin, null).andExpect(status().isBadRequest());
    }

    private ResultActions call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String token, String json) throws Exception {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    @Test
    void adminReadsSystemWideAuditLogWithFilters() throws Exception {
        String email = "audit.target@example.com";
        String token = JsonPath.read(signUpAndLogin(email), "$.accessToken");
        String targetId = userId(token);
        String admin = adminToken();

        call(post("/api/auth/login"), null, credentials(email, "sai-mat-khau")).andExpect(status().isUnauthorized());
        call(patch("/api/admin/users/" + targetId + "/status"), admin, "{\"status\":\"LOCKED\"}").andExpect(status().isOk());

        // Mới nhất trước, kèm cả tài khoản bị tác động và admin thực hiện
        call(get("/api/admin/auth-events").param("q", email).param("size", "2"), admin, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].type").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.content[0].user.email").value(email))
                .andExpect(jsonPath("$.content[0].actor.email").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.content[1].type").value("LOGIN_FAILED"))
                .andExpect(jsonPath("$.content[1].user.email").value(email))
                .andExpect(jsonPath("$.content[1].actor").doesNotExist())
                .andExpect(jsonPath("$.page.size").value(2));

        // Tìm theo admin cũng ra thao tác admin đó đã làm; ký tự % không phải ký tự đại diện
        call(get("/api/admin/auth-events").param("q", ADMIN_EMAIL).param("type", "ACCOUNT_LOCKED"), admin, null)
                .andExpect(jsonPath("$.content[0].user.email").value(email));
        call(get("/api/admin/auth-events").param("q", "%"), admin, null)
                .andExpect(jsonPath("$.page.totalElements").value(0));

        call(get("/api/admin/auth-events").param("q", email).param("from", "2999-01-01T00:00:00Z"), admin, null)
                .andExpect(jsonPath("$.page.totalElements").value(0));
        call(get("/api/admin/auth-events").param("q", email).param("to", "2000-01-01T00:00:00Z"), admin, null)
                .andExpect(jsonPath("$.page.totalElements").value(0));
        call(get("/api/admin/auth-events").param("q", email).param("from", "2000-01-01T00:00:00Z")
                .param("to", "2999-01-01T00:00:00Z"), admin, null)
                .andExpect(jsonPath("$.page.totalElements").value(org.hamcrest.Matchers.greaterThan(1)));

        call(get("/api/admin/auth-events").param("type", "KHONG_CO"), admin, null).andExpect(status().isBadRequest());

        String passenger = JsonPath.read(signUpAndLogin("audit.passenger@example.com"), "$.accessToken");
        call(get("/api/admin/auth-events"), passenger, null).andExpect(status().isForbidden());
        call(get("/api/admin/auth-events"), null, null).andExpect(status().isUnauthorized());
    }

    private String signUpAndLogin(String email) throws Exception {
        call(post("/api/auth/register"), null, "{\"email\":\"%s\",\"password\":\"%s\",\"fullName\":\"Người Dùng\"}".formatted(email, PASSWORD))
                .andExpect(status().isAccepted());
        String code = extractCode(awaitMail(email, "xác thực email", 1));
        call(post("/api/auth/verify-email"), null, codeJson(email, code)).andExpect(status().isNoContent());
        return call(post("/api/auth/login"), null, credentials(email, PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String adminToken() throws Exception {
        return JsonPath.read(call(post("/api/auth/login"), null, credentials(ADMIN_EMAIL, ADMIN_PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    private String userId(String token) throws Exception {
        return JsonPath.read(call(get("/api/me"), token, null).andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** Chờ mail thứ count gửi tới một địa chỉ. Mail gửi bất đồng bộ và GreenMail gom theo hộp thư, không theo thời gian. */
    private MimeMessage awaitMail(String to, String subject, int count) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (true) {
            List<MimeMessage> matched = new ArrayList<>();
            for (MimeMessage mail : greenMail.getReceivedMessages()) {
                if (mail.getAllRecipients()[0].toString().equals(to) && mail.getSubject().contains(subject)) {
                    matched.add(mail);
                }
            }
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

    private static String credentials(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }

    private static String codeJson(String email, String code) {
        return "{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code);
    }

    private static String refreshJson(String refreshToken) {
        return "{\"refreshToken\":\"%s\"}".formatted(refreshToken);
    }

    private static String profileJson(String newEmail) {
        return "{\"fullName\":\"Tên Mới\",\"email\":\"%s\",\"currentPassword\":\"%s\"}".formatted(newEmail, PASSWORD);
    }
}
