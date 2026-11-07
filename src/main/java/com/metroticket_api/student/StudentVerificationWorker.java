package com.metroticket_api.student;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import com.metroticket_api.ai.StudentCardExtraction;
import com.metroticket_api.ai.StudentCardExtractor;
import com.metroticket_api.ai.StudentCardExtractor.AiExtractionException;
import com.metroticket_api.config.AsyncConfig;

/**
 * Hàng đợi xác thực sinh viên lưu trong DB (không mất việc khi restart, chạy được nhiều instance).
 * Mỗi {@code app.student.worker-interval} lấy tối đa số hồ sơ bằng số thread AI còn trống và xử lý song song.
 */
@Component
public class StudentVerificationWorker {

    private static final Logger log = LoggerFactory.getLogger(StudentVerificationWorker.class);

    private final StudentVerificationProcessor processor;
    private final StudentCardExtractor extractor;
    private final ThreadPoolTaskExecutor executor;

    public StudentVerificationWorker(StudentVerificationProcessor processor, StudentCardExtractor extractor,
            @Qualifier(AsyncConfig.AI_EXECUTOR) ThreadPoolTaskExecutor executor) {
        this.processor = processor;
        this.extractor = extractor;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${app.student.worker-interval:5s}", initialDelayString = "${app.student.worker-interval:5s}")
    public void poll() {
        if (!extractor.available()) {
            return;
        }
        int free = executor.getMaxPoolSize() - executor.getActiveCount();
        if (free <= 0) {
            return;
        }
        List<UUID> ids = processor.claim(free);
        for (UUID id : ids) {
            try {
                executor.execute(() -> process(id));
            } catch (TaskRejectedException e) {
                processor.requeue(id);
            }
        }
    }

    void process(UUID id) {
        try {
            StudentVerificationProcessor.Job job = processor.load(id);
            if (job == null) {
                return;
            }
            if (job.image() == null) {
                processor.fail(id, "Không còn ảnh thẻ", false);
                return;
            }
            StudentCardExtraction result = extractor.extract(job.image(), job.mimeType());
            processor.complete(id, result);
        } catch (AiExtractionException e) {
            log.warn("AI đọc thẻ thất bại cho hồ sơ {}: {}", id, e.getMessage());
            processor.fail(id, e.getMessage(), e.retryable());
        } catch (RuntimeException e) {
            log.error("Lỗi không mong đợi khi xử lý hồ sơ sinh viên {}", id, e);
            processor.fail(id, "Lỗi hệ thống: " + e.getClass().getSimpleName(), true);
        }
    }
}
