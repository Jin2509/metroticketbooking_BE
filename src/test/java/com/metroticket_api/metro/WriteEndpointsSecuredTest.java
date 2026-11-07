package com.metroticket_api.metro;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import com.metroticket_api.metro.operation.OperationConfigController;
import com.metroticket_api.metro.staff.StaffStationController;
import com.metroticket_api.metro.train.TrainController;
import com.metroticket_api.parking.ParkingController;
import com.metroticket_api.ticket.TicketProductController;
import com.metroticket_api.ticket.TicketController;

/**
 * Lưới an toàn: mọi endpoint ghi của tàu, vận hành, vé, gửi xe, thông báo, thanh toán phải có @PreAuthorize theo quyền.
 * Ngoại lệ duy nhất có chủ đích: webhook SePay (xác thực bằng API key/HMAC, xem SecurityRegressionHttpTest).
 */
class WriteEndpointsSecuredTest {

    @Test
    void everyWriteEndpointHasPermissionCheck() {
        List<Class<?>> controllers = List.of(TrainController.class, OperationConfigController.class,
                StaffStationController.class, TicketProductController.class, TicketController.class, ParkingController.class,
                com.metroticket_api.metro.announcement.AnnouncementController.class,
                com.metroticket_api.payment.PaymentController.class,
                com.metroticket_api.student.StudentVerificationController.class,
                com.metroticket_api.green.GreenJourneyController.class,
                com.metroticket_api.notification.campaign.CampaignController.class,
                com.metroticket_api.notification.MyNotificationSettingsController.class);
        int checked = 0;
        for (Class<?> controller : controllers) {
            for (Method method : controller.getDeclaredMethods()) {
                boolean write = method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(PatchMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class);
                if (!write) {
                    continue;
                }
                PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
                assertThat(rule).as("%s.%s thiếu @PreAuthorize", controller.getSimpleName(), method.getName()).isNotNull();
                assertThat(rule.value()).containsAnyOf("hasAuthority(", "hasAnyAuthority(", "isAuthenticated()").doesNotContain("hasRole");
                checked++;
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(25);
    }
}
