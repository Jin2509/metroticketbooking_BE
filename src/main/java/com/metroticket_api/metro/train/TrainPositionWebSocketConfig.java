package com.metroticket_api.metro.train;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class TrainPositionWebSocketConfig implements WebSocketConfigurer {

    private final TrainPositionWebSocketHandler handler;
    private final List<String> allowedOrigins;

    public TrainPositionWebSocketConfig(TrainPositionWebSocketHandler handler,
            @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:5173}") List<String> allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/lines/*/train-positions")
                .setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    }
}
