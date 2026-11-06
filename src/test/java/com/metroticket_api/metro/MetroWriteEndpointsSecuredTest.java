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

import com.metroticket_api.metro.line.MetroLineController;
import com.metroticket_api.metro.station.LineTransferController;
import com.metroticket_api.metro.station.StationController;

/** Lưới an toàn: mọi endpoint ghi dữ liệu tuyến/ga/chuyển tuyến phải yêu cầu quyền STATION_MANAGE. */
class MetroWriteEndpointsSecuredTest {

    @Test
    void everyWriteEndpointRequiresStationManage() {
        List<Class<?>> controllers = List.of(MetroLineController.class, StationController.class, LineTransferController.class);
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
                assertThat(rule.value()).contains("STATION_MANAGE");
                checked++;
            }
        }
        assertThat(checked).isEqualTo(13);
    }
}
