package com.metroticket_api.verification;

import java.time.Clock;
import java.time.Duration;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Xoá các mã đã hết hạn quá 1 ngày, chạy lúc 3h sáng mỗi ngày. */
@Component
public class VerificationCleanupJob {

    private final EmailVerificationChallengeRepository repository;
    private final Clock clock;

    public VerificationCleanupJob(EmailVerificationChallengeRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public void purgeExpired() {
        repository.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(1)));
    }
}
