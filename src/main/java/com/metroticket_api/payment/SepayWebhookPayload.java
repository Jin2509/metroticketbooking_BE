package com.metroticket_api.payment;

import tools.jackson.databind.JsonNode;

/**
 * Body webhook SePay (developer.sepay.vn): id, gateway, transactionDate, accountNumber, subAccount, code, content,
 * transferType ("in"/"out"), transferAmount, accumulated, referenceCode, description.
 */
public record SepayWebhookPayload(long id, String gateway, String transactionDate, String accountNumber,
        String subAccount, String code, String content, String transferType, long transferAmount,
        String referenceCode, String description) {

    public static SepayWebhookPayload of(JsonNode n) {
        if (!n.has("id") || !n.has("transferAmount") || !n.has("transferType")) {
            throw new IllegalArgumentException("Thiếu trường bắt buộc id/transferAmount/transferType");
        }
        return new SepayWebhookPayload(n.path("id").asLong(), text(n, "gateway"), text(n, "transactionDate"),
                text(n, "accountNumber"), text(n, "subAccount"), text(n, "code"), text(n, "content"),
                text(n, "transferType"), n.path("transferAmount").asLong(), text(n, "referenceCode"),
                text(n, "description"));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    public boolean incoming() {
        return "in".equalsIgnoreCase(transferType);
    }
}
