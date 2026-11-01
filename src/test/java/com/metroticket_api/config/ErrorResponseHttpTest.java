package com.metroticket_api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.jayway.jsonpath.JsonPath;
import com.metroticket_api.TestcontainersConfiguration;

/**
 * Chạy trên cổng thật vì MockMvc không có bước ERROR dispatch của Tomcat: lỗi từng bị
 * Security chặn ở /error nên JSON hỏng trả 401, còn 400/404 của admin trả 403.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ErrorResponseHttpTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void errorsKeepTheirRealStatusAndBody() throws Exception {
        assertThat(send("POST", "/api/auth/login", null, "{hỏng").statusCode()).isEqualTo(400);

        String login = send("POST", "/api/auth/login", null,
                "{\"email\":\"admin@metroticket.test\",\"password\":\"Admin@12345\"}").body();
        String token = JsonPath.read(login, "$.accessToken");

        HttpResponse<String> notFound = send("GET", "/api/admin/users/" + UUID.randomUUID(), token, null);
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/admin/users/abc", token, null).statusCode()).isEqualTo(400);
        assertThat(send("GET", "/api/khong-co", token, null).statusCode()).isEqualTo(404);
        assertThat(send("DELETE", "/api/me", token, null).statusCode()).isEqualTo(405);

        HttpResponse<String> badRole = send("PUT", "/api/admin/users/" + UUID.randomUUID() + "/roles", token, "{\"roles\":[\"STAFF\"]}");
        assertThat(badRole.statusCode()).isEqualTo(404);
        assertThat(badRole.headers().firstValue("Content-Type")).hasValue("application/problem+json");

        // Không có token vẫn là 401, thiếu quyền vẫn do lớp Security xử lý
        assertThat(send("GET", "/api/me", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void requestsAreLoggedWithoutQueryOrCredentials(CapturedOutput output) throws Exception {
        send("POST", "/api/auth/login", null, "{\"email\":\"log@example.com\",\"password\":\"bi-mat-khong-log\"}");
        send("GET", "/api/me?q=tu-khoa-rieng", "token-rac", null);

        assertThat(output.getOut()).contains("POST /api/auth/login -> 401").contains("GET /api/me -> 401")
                .doesNotContain("bi-mat-khong-log").doesNotContain("tu-khoa-rieng").doesNotContain("token-rac");
    }

    private HttpResponse<String> send(String method, String path, String token, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
