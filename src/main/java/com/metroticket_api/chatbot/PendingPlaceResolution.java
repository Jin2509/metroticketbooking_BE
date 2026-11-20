package com.metroticket_api.chatbot;

import java.time.Instant;
import java.util.List;

import com.metroticket_api.external.facility.GeoapifyGeocodingClient.PlaceCandidate;

record PendingPlaceResolution(List<PlaceCandidate> candidates, String originalQuestion, Instant expiresAt) {
    PendingPlaceResolution {
        candidates = List.copyOf(candidates);
    }
}
