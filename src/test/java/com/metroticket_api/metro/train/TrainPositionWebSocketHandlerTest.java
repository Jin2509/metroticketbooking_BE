package com.metroticket_api.metro.train;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.line.MetroLineRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class TrainPositionWebSocketHandlerTest {

    @Test
    void sendsTrainPositionsWithoutStationMetadataWhenClientConnects() throws Exception {
        MetroLineRepository lines = mock(MetroLineRepository.class);
        TrainPositionService positions = mock(TrainPositionService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        MetroLine line = mock(MetroLine.class);
        when(line.getId()).thenReturn(1L);
        when(line.getCode()).thenReturn("BTS-01");
        when(lines.findByCode("BTS-01")).thenReturn(Optional.of(line));

        TrainPositionService.TrainPositionResponse train = new TrainPositionService.TrainPositionResponse(
                "TR-001", "BTS-01", 10.7731, 106.6983, "ST002", 120, "RUNNING", "ST011");
        when(positions.getPositions(1L)).thenReturn(List.of(train));

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.getUri()).thenReturn(URI.create("/ws/lines/BTS-01/train-positions"));
        when(session.isOpen()).thenReturn(true);

        TrainPositionWebSocketHandler handler = new TrainPositionWebSocketHandler(lines, positions, objectMapper);
        handler.afterConnectionEstablished(session);

        var messageCaptor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(messageCaptor.capture());
        JsonNode payload = objectMapper.readTree(messageCaptor.getValue().getPayload());
        assertTrue(payload.toString().contains("\"type\":\"positions\""));
        assertTrue(payload.toString().contains("\"lineId\":\"BTS-01\""));
        assertTrue(payload.toString().contains("\"trainId\":\"TR-001\""));
        assertFalse(payload.toString().contains("\"stations\""));
    }
}
