package com.metroticket_api.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.metroticket_api.security.CurrentUser;

/** Ghi và đọc lịch sử bảo mật. Ghi chung transaction với thao tác nghiệp vụ để không lệch nhau. */
@Service
public class AuthEventService {

    private static final Duration RETENTION = Duration.ofDays(90);
    private static final int MAX_USER_AGENT_LENGTH = 255;

    private final AuthEventRepository repository;
    private final Clock clock;

    public AuthEventService(AuthEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Chính người dùng thực hiện: lưu IP và thiết bị của request hiện tại. */
    @Transactional
    public void record(UUID userId, AuthEventType type) {
        HttpServletRequest request = currentRequest();
        String ip = request == null ? null : request.getRemoteAddr();
        String userAgent = request == null ? null : truncate(request.getHeader(HttpHeaders.USER_AGENT));
        repository.save(new AuthEvent(userId, null, type, ip, userAgent, clock.instant()));
    }

    /** Admin thực hiện: không lưu IP/thiết bị vì đó là của admin, người dùng xem lịch sử sẽ hiểu nhầm là của mình. */
    @Transactional
    public void recordByAdmin(UUID userId, AuthEventType type) {
        repository.save(new AuthEvent(userId, CurrentUser.id(), type, null, null, clock.instant()));
    }

    /** Luôn mới nhất trước, không nhận sort từ client. */
    @Transactional(readOnly = true)
    public Page<AuthEvent> list(UUID userId, Pageable pageable) {
        return repository.findByUserId(userId, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    /** Xoá lịch sử quá 90 ngày, chạy lúc 3h45 sáng mỗi ngày. */
    @Scheduled(cron = "0 45 3 * * *", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public void purgeOld() {
        repository.deleteOlderThan(clock.instant().minus(RETENTION));
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_USER_AGENT_LENGTH ? value : value.substring(0, MAX_USER_AGENT_LENGTH);
    }
}
