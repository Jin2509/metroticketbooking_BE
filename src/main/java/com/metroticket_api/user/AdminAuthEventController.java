package com.metroticket_api.user;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.auth.AuthEvent;
import com.metroticket_api.auth.AuthEventRepository;
import com.metroticket_api.auth.AuthEventType;
import com.metroticket_api.user.UserDtos.AuditLogView;

/** Nhật ký bảo mật của toàn hệ thống. Cùng quyền với lịch sử của từng tài khoản (USER_VIEW). */
@RestController
@RequestMapping("/api/admin/auth-events")
public class AdminAuthEventController {

    private final AuthEventRepository events;
    private final UserRepository users;

    public AdminAuthEventController(AuthEventRepository events, UserRepository users) {
        this.events = events;
        this.users = users;
    }

    /**
     * Luôn mới nhất trước, không nhận sort từ client.
     * q: tìm trong email hoặc họ tên của tài khoản hoặc người thực hiện; from: tính từ mốc này; to: trước mốc này.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('USER_VIEW')")
    @Transactional(readOnly = true)
    public Page<AuditLogView> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) AuthEventType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20) Pageable pageable) {
        Sort newestFirst = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
        Page<AuthEvent> page = events.findAll(matching(q, type, from, to),
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), newestFirst));

        // Nạp tài khoản theo lô thay vì từng dòng, mỗi trang chỉ tốn một câu truy vấn
        Set<UUID> ids = new HashSet<>();
        for (AuthEvent event : page) {
            ids.add(event.getUserId());
            if (event.getActorId() != null) {
                ids.add(event.getActorId());
            }
        }
        Map<UUID, User> accounts = users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return page.map(event -> AuditLogView.of(event, accounts.get(event.getUserId()), accounts.get(event.getActorId())));
    }

    private static Specification<AuthEvent> matching(String q, AuthEventType type, Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (type != null) {
                conditions.add(cb.equal(root.get("type"), type));
            }
            if (from != null) {
                conditions.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                conditions.add(cb.lessThan(root.get("createdAt"), to));
            }
            if (q != null && !q.isBlank()) {
                conditions.add(cb.or(
                        root.get("userId").in(accountsMatching(q, query, cb)),
                        root.get("actorId").in(accountsMatching(q, query, cb))));
            }
            return cb.and(conditions.toArray(Predicate[]::new));
        };
    }

    private static Subquery<UUID> accountsMatching(String q, CriteriaQuery<?> query, CriteriaBuilder cb) {
        String pattern = AdminUserController.likePattern(q);
        Subquery<UUID> matched = query.subquery(UUID.class);
        Root<User> account = matched.from(User.class);
        return matched.select(account.get("id")).where(cb.or(
                cb.like(cb.lower(account.get("email")), pattern, '\\'),
                cb.like(cb.lower(account.get("fullName")), pattern, '\\')));
    }
}
