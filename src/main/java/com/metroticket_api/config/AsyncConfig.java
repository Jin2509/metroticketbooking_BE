package com.metroticket_api.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Bật @Async và tạo một thread pool riêng cho việc gửi mail,
 * để request HTTP không phải chờ SMTP (có thể mất 1-5 giây).
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String MAIL_EXECUTOR = "mailExecutor";
    public static final String AI_EXECUTOR = "aiExecutor";

    /**
     * Pool riêng cho các lời gọi AI (mỗi lời gọi có thể mất hàng chục giây). Không dùng chung scheduler
     * mặc định (1 thread) vì sẽ chặn các job khác như phát vị trí tàu mỗi giây.
     */
    @Bean(name = AI_EXECUTOR)
    public ThreadPoolTaskExecutor aiExecutor(
            @org.springframework.beans.factory.annotation.Value("${app.ai.gemini.concurrency:2}") int concurrency) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("ai-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean(name = MAIL_EXECUTOR)
    public Executor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("mail-");
        // Khi tắt ứng dụng, chờ các mail đang gửi dở hoàn thành
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
