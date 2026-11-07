package com.metroticket_api.notification.campaign;

public enum CampaignStatus {
    DRAFT, SCHEDULED, SENDING, SENT, CANCELLED, FAILED;

    public boolean editable() {
        return this == DRAFT || this == SCHEDULED;
    }
}
