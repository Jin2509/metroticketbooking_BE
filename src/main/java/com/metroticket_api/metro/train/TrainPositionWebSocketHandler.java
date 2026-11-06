package com.metroticket_api.metro.train;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.train.TrainPositionService.TrainPositionResponse;

import tools.jackson.databind.ObjectMapper;

@Component
public class TrainPositionWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(TrainPositionWebSocketHandler.class);

    private final MetroLineRepository lines;
    private final TrainPositionService positions;
    private final ObjectMapper objectMapper;
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    public TrainPositionWebSocketHandler(MetroLineRepository lines, TrainPositionService positions,
            ObjectMapper objectMapper) {
        this.lines = lines;
        this.positions = positions;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (session.getUri() == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        String[] pathSegments = session.getUri().getPath().split("/");
        if (pathSegments.length != 5 || !"ws".equals(pathSegments[1]) || !"lines".equals(pathSegments[2])
                || !"train-positions".equals(pathSegments[4])) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        String lineCode = pathSegments[3];
        try {
            MetroLine line = lines.findByCode(lineCode)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tuyến"));
            Subscription subscription = new Subscription(session, line.getId(), line.getCode());
            subscriptions.put(session.getId(), subscription);
            sendSnapshot(session, subscription);
        } catch (ResponseStatusException exception) {
            closeWithError(session, exception.getStatusCode().value(), exception.getReason());
        } catch (IOException exception) {
            subscriptions.remove(session.getId());
            logger.warn("Could not send initial train positions to session {}", session.getId(), exception);
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        subscriptions.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        subscriptions.remove(session.getId());
        logger.warn("Train position WebSocket transport failed for session {}", session.getId(), exception);
    }

    @Scheduled(fixedRate = 1000)
    public void broadcastPositions() {
        for (Map.Entry<String, Subscription> entry : subscriptions.entrySet()) {
            WebSocketSession session = findOpenSession(entry.getKey());
            if (session == null) {
                subscriptions.remove(entry.getKey());
                continue;
            }
            try {
                sendSnapshot(session, entry.getValue());
            } catch (ResponseStatusException exception) {
                closeWithError(session, exception.getStatusCode().value(), exception.getReason());
            } catch (IOException exception) {
                subscriptions.remove(session.getId());
                logger.warn("Could not send train positions to session {}", session.getId(), exception);
            } catch (RuntimeException exception) {
                subscriptions.remove(session.getId());
                logger.error("Unexpected failure while simulating train positions for session {}", session.getId(),
                        exception);
                closeWithError(session, HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "Không thể mô phỏng vị trí tàu lúc này");
            }
        }
    }

    private WebSocketSession findOpenSession(String sessionId) {
        Subscription subscription = subscriptions.get(sessionId);
        return subscription == null || !subscription.session().isOpen() ? null : subscription.session();
    }

    private void sendSnapshot(WebSocketSession session, Subscription subscription) throws IOException {
        List<TrainPositionResponse> currentPositions = positions.getPositions(subscription.lineId());
        TrainPositionUpdate update = new TrainPositionUpdate(
                "positions",
                subscription.lineCode(),
                Instant.now().toString(),
                currentPositions);
        String json = objectMapper.writeValueAsString(update);
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        }
    }

    private void closeWithError(WebSocketSession session, int status, String reason) {
        subscriptions.remove(session.getId());
        try {
            String json = objectMapper.writeValueAsString(new TrainPositionError("error", status, reason));
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                    session.close(CloseStatus.POLICY_VIOLATION);
                }
            }
        } catch (IOException exception) {
            logger.warn("Could not send train position error to session {}", session.getId(), exception);
        }
    }

    private record Subscription(WebSocketSession session, Long lineId, String lineCode) {
    }

    public record TrainPositionUpdate(String type, String lineId, String updatedAt, List<TrainPositionResponse> trains) {
    }

    public record TrainPositionError(String type, int status, String message) {
    }
}
