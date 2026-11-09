package com.metroticket_api.loyalty;

import java.util.UUID;

/** Một lượt check-in đã được chấp nhận, phát trong transaction soát vé. */
public record CheckInAccepted(UUID userId, Long validationId) {
}
