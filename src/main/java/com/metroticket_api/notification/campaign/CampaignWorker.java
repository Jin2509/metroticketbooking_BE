package com.metroticket_api.notification.campaign;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Mỗi 10 giây gửi các chiến dịch đã đến giờ hẹn (nhiều instance an toàn nhờ SKIP LOCKED). */
@Component
public class CampaignWorker {

    private static final Logger log = LoggerFactory.getLogger(CampaignWorker.class);

    private final CampaignService service;

    public CampaignWorker(CampaignService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.notification.campaign-interval:10s}",
            initialDelayString = "${app.notification.campaign-interval:10s}")
    public void poll() {
        for (int i = 0; i < 10; i++) {
            try {
                if (!service.runDue()) {
                    return;
                }
            } catch (RuntimeException e) {
                log.error("Gửi chiến dịch thông báo thất bại", e);
                service.failDue(e.getMessage());
                return;
            }
        }
    }

}
