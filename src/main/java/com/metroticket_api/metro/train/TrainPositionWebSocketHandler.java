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
    /** Giới hạn kết nối ẩn danh đồng thời (endpoint công khai). */
    static final int MAX_SESSIONS = 2_000;
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

        if (subscriptions.size() >= MAX_SESSIONS) {
            closeWithError(session, HttpStatus.SERVICE_UNAVAILABLE.value(), "Máy chủ đang quá tải, vui lòng thử lại sau");
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

    /**
     * Mỗi giây tính vị trí MỘT lần cho mỗi tuyến có người xem rồi gửi cùng một JSON cho mọi kết nối của tuyến đó
     * (trước đây mỗi kết nối tự tính và truy vấn DB riêng → N kết nối = N lần truy vấn mỗi giây).
     */
    @Scheduled(fixedRate = 1000)
    public void broadcastPositions() {
        Map<Long, List<Subscription>> byLine = new java.util.HashMap<>();
        for (Map.Entry<String, Subscription> entry : subscriptions.entrySet()) {
            if (!entry.getValue().session().isOpen()) {
                subscriptions.remove(entry.getKey());
                continue;
            }
            byLine.computeIfAbsent(entry.getValue().lineId(), id -> new java.util.ArrayList<>()).add(entry.getValue());
        }
        for (Map.Entry<Long, List<Subscription>> line : byLine.entrySet()) {
            String json;
            try {
                json = snapshotJson(line.getKey(), line.getValue().get(0).lineCode());
            } catch (ResponseStatusException exception) {
                line.getValue().forEach(sub -> closeWithError(sub.session(), exception.getStatusCode().value(),
                        exception.getReason()));
                continue;
            } catch (RuntimeException exception) {
                logger.error("Unexpected failure while simulating train positions for line {}", line.getKey(), exception);
                line.getValue().forEach(sub -> closeWithError(sub.session(), HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "Không thể mô phỏng vị trí tàu lúc này"));
                continue;
            }
            for (Subscription sub : line.getValue()) {
                try {
                    send(sub.session(), json);
                } catch (IOException exception) {
                    subscriptions.remove(sub.session().getId());
                    logger.warn("Could not send train positions to session {}", sub.session().getId(), exception);
                }
            }
        }
    }

    private void sendSnapshot(WebSocketSession session, Subscription subscription) throws IOException {
        send(session, snapshotJson(subscription.lineId(), subscription.lineCode()));
    }

    private String snapshotJson(Long lineId, String lineCode) {
        List<TrainPositionResponse> currentPositions = positions.getPositions(lineId);
        return objectMapper.writeValueAsString(new TrainPositionUpdate("positions", lineCode, Instant.now().toString(),
                currentPositions));
    }

    private void send(WebSocketSession session, String json) throws IOException {
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
