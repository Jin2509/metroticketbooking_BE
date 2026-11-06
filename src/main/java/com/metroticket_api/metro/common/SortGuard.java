package com.metroticket_api.metro.common;

import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Chặn tham số sort không hợp lệ từ client: nếu để lọt xuống JPA sẽ thành lỗi 500.
 */
public final class SortGuard {

    private SortGuard() {
    }

    public static void requireAllowed(Pageable pageable, Set<String> allowed) {
        if (pageable == null || pageable.getSort().isUnsorted()) {
            return;
        }
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Không thể sắp xếp theo '" + order.getProperty() + "'. Cho phép: " + String.join(", ", allowed.stream().sorted().toList()));
            }
        }
    }
}
