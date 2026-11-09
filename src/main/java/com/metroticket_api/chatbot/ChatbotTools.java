package com.metroticket_api.chatbot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.metroticket_api.external.bus.BusService;
import com.metroticket_api.external.facility.FacilityService;
import com.metroticket_api.loyalty.LoyaltyService;
import com.metroticket_api.metro.map.MapService;
import com.metroticket_api.metro.map.RoutePlanner;
import com.metroticket_api.metro.station.LineStation;
import com.metroticket_api.metro.station.LineStationRepository;
import com.metroticket_api.metro.station.Station;
import com.metroticket_api.metro.station.StationRepository;
import com.metroticket_api.metro.train.TrainPositionService;
import com.metroticket_api.ticket.FareService;
import com.metroticket_api.ticket.TicketProductService;
import com.metroticket_api.ticket.TicketRepository;

import tools.jackson.databind.JsonNode;

/**
 * Các hàm chỉ đọc cung cấp cho chatbot. Mã người dùng luôn lấy từ phiên đăng nhập, không lấy từ tham số do mô hình sinh
 * ra, nên chatbot không thể đọc dữ liệu của tài khoản khác. Không hàm nào trả mã QR hay thông tin thanh toán.
 */
@Component
public class ChatbotTools {

    private static final int NEARBY_RADIUS_METERS = 800;
    private static final double BUS_RADIUS_KM = 0.8;
    private static final int MAX_ITEMS = 10;

    private final TicketProductService products;
    private final TicketRepository tickets;
    private final LoyaltyService loyalty;
    private final StationRepository stations;
    private final LineStationRepository lineStations;
    private final MapService map;
    private final TrainPositionService trainPositions;
    private final FacilityService facilities;
    private final BusService buses;
    private final TransactionTemplate readOnly;
    private final FareService fares;

    public ChatbotTools(TicketProductService products, TicketRepository tickets, LoyaltyService loyalty,
            StationRepository stations, LineStationRepository lineStations, MapService map,
            TrainPositionService trainPositions, FacilityService facilities, BusService buses, FareService fares,
            PlatformTransactionManager transactionManager) {
        this.fares = fares;
        this.products = products;
        this.tickets = tickets;
        this.loyalty = loyalty;
        this.stations = stations;
        this.lineStations = lineStations;
        this.map = map;
        this.trainPositions = trainPositions;
        this.facilities = facilities;
        this.buses = buses;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    public List<Map<String, Object>> declarations() {
        return List.of(
                function("get_ticket_prices", "Danh sách loại vé đang bán và giá", Map.of()),
                function("get_single_fare", "Giá vé lượt giữa hai ga", Map.of(
                        "from_station", stringParam("Tên ga đi"), "to_station", stringParam("Tên ga đến"))),
                function("search_route", "Tìm lộ trình giữa hai ga", Map.of(
                        "from_station", stringParam("Tên ga đi"), "to_station", stringParam("Tên ga đến"))),
                function("get_next_trains", "Các chuyến tàu sắp đến một ga", Map.of("station", stringParam("Tên ga"))),
                function("get_nearby_places", "Tiện ích và trạm xe buýt quanh một ga", Map.of("station", stringParam("Tên ga"))),
                function("get_my_tickets", "Vé của người dùng đang đăng nhập", Map.of()),
                function("get_my_points", "Điểm CPoint và hạng của người dùng đang đăng nhập", Map.of()));
    }

    /** Lỗi của từng hàm được trả cho mô hình dưới dạng dữ liệu để chatbot báo "không tìm thấy" thay vì thất bại. */
    public Map<String, Object> execute(String name, JsonNode args, UUID userId) {
        try {
            return Map.of("result", readOnly.execute(status -> run(name, args, userId)));
        } catch (RuntimeException e) {
            return Map.of("error", "Không lấy được dữ liệu");
        }
    }

    private Object run(String name, JsonNode args, UUID userId) {
        return switch (name) {
            case "get_ticket_prices" -> products.listActive();
            case "get_single_fare" -> fare(text(args, "from_station"), text(args, "to_station"));
            case "search_route" -> route(text(args, "from_station"), text(args, "to_station"));
            case "get_next_trains" -> nextTrains(text(args, "station"));
            case "get_nearby_places" -> nearby(text(args, "station"));
            case "get_my_tickets" -> myTickets(userId);
            case "get_my_points" -> loyalty.summary(userId);
            default -> Map.of("message", "Hàm không được hỗ trợ");
        };
    }

    private Object fare(String from, String to) {
        Optional<Station> origin = station(from);
        Optional<Station> destination = station(to);
        if (origin.isEmpty() || destination.isEmpty()) {
            return Map.of("message", "Không tìm thấy ga");
        }
        return fares.quote(origin.get().getId(), destination.get().getId())
                .<Object>map(q -> q).orElse(Map.of("message", "Không tìm thấy lộ trình"));
    }

    private Object route(String from, String to) {
        Optional<Station> origin = station(from);
        Optional<Station> destination = station(to);
        if (origin.isEmpty() || destination.isEmpty()) {
            return Map.of("message", "Không tìm thấy ga");
        }
        RoutePlanner.Journey journey = map.route(origin.get().getId(), destination.get().getId(), RoutePlanner.Optimize.SHORTEST);
        return journey;
    }

    private Object nextTrains(String name) {
        Optional<Station> found = station(name);
        if (found.isEmpty()) {
            return Map.of("message", "Không tìm thấy ga");
        }
        List<LineStation> lines = lineStations.findByStationId(found.get().getId());
        if (lines.isEmpty()) {
            return Map.of("message", "Ga chưa thuộc tuyến nào");
        }
        return trainPositions.getArrivals(lines.get(0).getLine().getCode(), found.get().getCode(), 3);
    }

    private Object nearby(String name) {
        Optional<Station> found = station(name);
        if (found.isEmpty()) {
            return Map.of("message", "Không tìm thấy ga");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("facilities", facilities.getNearbyFacilities(found.get().getId(), null, NEARBY_RADIUS_METERS).stream().limit(MAX_ITEMS).toList());
        result.put("busStops", buses.getNearbyStops(found.get().getId(), BUS_RADIUS_KM, MAX_ITEMS));
        return result;
    }

    private List<Map<String, Object>> myTickets(UUID userId) {
        return tickets.findByUserIdOrderByPurchasedAtDesc(userId, PageRequest.of(0, MAX_ITEMS)).stream()
                .map(t -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ticketCode", t.getTicketCode());
                    row.put("product", t.getProduct().getName());
                    row.put("status", t.getStatus().name());
                    row.put("validUntil", t.getValidUntil().toString());
                    return row;
                }).toList();
    }

    private Optional<Station> station(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return stations.searchByName(name.trim(), PageRequest.of(0, 1)).stream().findFirst();
    }

    private static String text(JsonNode args, String field) {
        return args == null ? null : args.path(field).asString(null);
    }

    private static Map<String, Object> stringParam(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> function(String name, String description, Map<String, Object> properties) {
        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("name", name);
        declaration.put("description", description);
        if (!properties.isEmpty()) {
            declaration.put("parameters", Map.of("type", "object", "properties", properties, "required", List.copyOf(properties.keySet())));
        }
        return declaration;
    }
}
