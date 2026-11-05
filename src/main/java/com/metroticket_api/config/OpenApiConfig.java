package com.metroticket_api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Cấu hình Swagger/OpenAPI cho MetroTicket backend.
 *
 * <p>Swagger UI giúp test API nhanh, đồng thời hiển thị rõ danh sách endpoint, request/response và cách dùng với JWT Bearer token.</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI metroOpenAPI() {
        final String securitySchemeName = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("MetroTicket API")
                        .description("API backend cho hệ thống MetroTicket, bao gồm auth, user, RBAC, notification và domain Metro. " +
                                "Workflow phê duyệt được thống nhất theo mô hình DRAFT -> PENDING_APPROVAL -> APPROVED/REJECTED; operation_config còn giữ version để tạo draft mới từ bản approved cũ.")
                        .version("v1.0")
                        .contact(new Contact()
                                .name("MetroTicket Team")
                                .email("support@metroticket.local")))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Dùng access token JWT để gọi API cần xác thực.")));
    }
}
