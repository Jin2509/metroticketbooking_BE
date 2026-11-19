package com.metroticket_api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class DemoProductDataMigrationTest {

    @Test
    void migrationCreatesDemoAccountsAndSeedsConsistentDemoData() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            migrate(postgres, "19", Map.of(
                    "admin-email", "",
                    "demoProductData", "false",
                    "demoProfile", ""));

            migrate(postgres, null, Map.of(
                    "admin-email", "",
                    "demoProductData", "true",
                    "demoProfile", "dev"));

            try (Connection connection = connection(postgres)) {
                assertThat(count(connection, "select count(*) from app_user where email like '%@metroticket.test'"))
                        .isEqualTo(18L);
                assertThat(count(connection, "select count(*) from ticket where ticket_code like 'DEMO-TK-%'"))
                        .isEqualTo(5L);
                assertThat(count(connection, "select count(*) from ticket where ticket_code like 'DEMO-EXT-TK-%'"))
                        .isEqualTo(36L);
                assertThat(count(connection, "select count(*) from payment_order where order_code like 'MTQDEMO%'"))
                        .isEqualTo(2L);
                assertThat(count(connection, "select count(*) from payment_transaction "
                        + "where provider_ref like 'DEMO-EXT-%'")).isEqualTo(33L);
                assertThat(count(connection, "select count(*) from parking_session where session_code like 'DEMO-PS-%'"))
                        .isEqualTo(2L);
                assertThat(count(connection, "select count(*) from parking_session "
                        + "where session_code like 'DEMO-EXT-PS-%'")).isEqualTo(12L);
                assertThat(count(connection, "select count(*) from parking_monthly_pass "
                        + "where pass_code like 'DEMO-EXT-PASS-%'")).isEqualTo(6L);
                assertThat(count(connection, "select count(*) from notification_campaign "
                        + "where id in (md5('metroticket-extended-demo-campaign-SENT')::uuid, "
                        + "md5('metroticket-extended-demo-campaign-SCHEDULED')::uuid, "
                        + "md5('metroticket-extended-demo-campaign-DRAFT')::uuid)")).isEqualTo(3L);
                assertThat(count(connection, "select count(*) from notification_delivery d "
                        + "join notification n on n.id = d.notification_id "
                        + "where n.campaign_id = md5('metroticket-extended-demo-campaign-SENT')::uuid "
                        + "and d.channel = 'EMAIL' and d.status = 'SENT'")).isEqualTo(16L);
                assertThat(count(connection, "select count(*) from notification_campaign_user "
                        + "where campaign_id = md5('metroticket-extended-demo-campaign-DRAFT')::uuid")).isEqualTo(2L);
                assertThat(count(connection, "select count(*) from operation_config oc "
                        + "join metro_line ml on ml.id = oc.line_id where ml.code = 'BTS-01' "
                        + "and oc.status in ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED')")).isEqualTo(4L);
                assertThat(count(connection, "select count(*) from ticket_product_audit "
                        + "where changes like '%dữ liệu demo%' or changes like '%mẫu%'")).isEqualTo(3L);
                assertThat(count(connection, "select count(*) from auth_event "
                        + "where type = 'ACCOUNT_CREATED_BY_ADMIN' and user_id in "
                        + "(select id from app_user where email like 'demo.passenger.__@metroticket.test')"))
                        .isEqualTo(12L);
                assertThat(count(connection, "select count(*) from green_trip "
                        + "where counted = false and not_counted_reason is not null")).isEqualTo(3L);
                assertThat(count(connection, "select count(*) from student_verification "
                        + "where declared_student_code like 'DEMO-SV-%'")).isEqualTo(4L);
                assertThat(count(connection, "select count(*) from student_verification_image svi "
                        + "join student_verification sv on sv.id = svi.verification_id "
                        + "where (sv.declared_student_code like 'SV202600%' "
                        + "or sv.declared_student_code like 'DEMO-SV-%') "
                        + "and octet_length(svi.content) > 0")).isEqualTo(6L);
                assertThat(count(connection, "select count(*) from green_trip gt join ticket t on t.id = gt.ticket_id "
                        + "where t.ticket_code = 'DEMO-TK-002'")).isEqualTo(1L);
                assertThat(count(connection, "select count(*) from ticket_validation tv join ticket t on t.id = tv.ticket_id "
                        + "where t.ticket_code = 'DEMO-TK-002' and tv.result = 'ACCEPTED'")).isEqualTo(2L);
                assertThat(count(connection, "select count(*) from app_user "
                        + "where email = 'passenger.demo@metroticket.test' "
                        + "and password_hash = '{noop}Doi-Mat-Khau-Nay@123'")).isEqualTo(1L);
                assertThat(count(connection, "select count(*) from app_user u "
                        + "join user_role ur on ur.user_id = u.id join role r on r.id = ur.role_id "
                        + "where u.email = 'admin.demo@metroticket.test' and r.code = 'ADMIN'")).isEqualTo(1L);
                assertThat(count(connection, "select count(*) from app_user "
                        + "where email = 'locked.demo@metroticket.test' and status = 'LOCKED'")).isEqualTo(1L);
            }
        }
    }

    @Test
    void migrationSkipsDemoDataOutsideLocalAndDevProfiles() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")) {
            postgres.start();
            migrate(postgres, null, Map.of(
                    "admin-email", "",
                    "demoProductData", "true",
                    "demoProfile", "prod"));

            try (Connection connection = connection(postgres)) {
                assertThat(count(connection, "select count(*) from app_user where email like '%@metroticket.test'"))
                        .isZero();
                assertThat(count(connection, "select count(*) from ticket where ticket_code like 'DEMO-TK-%'"))
                        .isZero();
            }
        }
    }

    private static void migrate(PostgreSQLContainer postgres, String target, Map<String, String> placeholders) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .placeholders(placeholders);
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        configuration.load().migrate();
    }

    private static Connection connection(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }
}
