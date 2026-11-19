package com.metroticket_api.chatbot;

import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.metroticket_api.external.bus.BusService;
import com.metroticket_api.external.facility.FacilityService;
import com.metroticket_api.external.facility.GeoapifyGeocodingClient;
import com.metroticket_api.external.facility.GeoapifyGeocodingClient.PlaceCandidate;
import com.metroticket_api.loyalty.LoyaltyService;
import com.metroticket_api.metro.common.StationStatus;
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
 *
 * <p>{@code find_place_and_nearest_stations} geocodes địa danh qua Geoapify rồi tìm ga
 * ACTIVE gần tọa độ bằng khoảng cách đường thẳng. Kết quả mơ hồ trả
 * {@code CLARIFICATION_REQUIRED}, để người dùng xác nhận thay vì tự chọn địa danh.
 */
@Component
public class ChatbotTools {

    private static final Logger log = LoggerFactory.getLogger(ChatbotTools.class);
    private static final int NEARBY_RADIUS_METERS = 800;
    private static final double BUS_RADIUS_KM = 0.8;
    private static final int MAX_ITEMS = 10;
    private static final int MAX_GEOCODE_CHOICES = 5;
    private static final int MAX_STATION_DISTANCE_METERS = 5000;
    private static final double AMBIGUOUS_CONFIDENCE_GAP = 0.15;

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
    private final GeoapifyGeocodingClient geocoding;

    public ChatbotTools(TicketProductService products, TicketRepository tickets, LoyaltyService loyalty,
            StationRepository stations, LineStationRepository lineStations, MapService map,
            TrainPositionService trainPositions, FacilityService facilities, BusService buses, FareService fares,
            GeoapifyGeocodingClient geocoding, PlatformTransactionManager transactionManager) {
        this.fares = fares;
        this.geocoding = geocoding;
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
                function("find_place_and_nearest_stations",
                        "Xác định địa danh người dùng nhắc đến ở Việt Nam và tìm ga metro đang hoạt động gần nhất. Nếu kết quả mơ hồ, hỏi người dùng chọn một địa chỉ.",
                        Map.of("place_name", stringParam("Tên địa danh, địa chỉ hoặc điểm đến"))),
                function("get_my_tickets", "Vé của người dùng đang đăng nhập", Map.of()),
                function("get_my_points", "Điểm CPoint và hạng của người dùng đang đăng nhập", Map.of()));
    }

    /** Lỗi tool được trả thành dữ liệu rõ ràng để chatbot không suy đoán kết quả. */
    public Map<String, Object> execute(String name, JsonNode args, UUID userId) {
        try {
            if ("find_place_and_nearest_stations".equals(name)) {
                return Map.of("result", locatePlace(text(args, "place_name")));
            }
            return Map.of("result", readOnly.execute(status -> run(name, args, userId)));
        } catch (RuntimeException e) {
            log.warn("Chatbot tool {} failed: {}", name, e.getMessage());
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

    private Map<String, Object> locatePlace(String query) {
        List<PlaceCandidate> candidates = geocoding.search(query).stream().limit(MAX_GEOCODE_CHOICES).toList();
        if (candidates.isEmpty()) {
            return Map.of("status", "NOT_FOUND", "query", query == null ? "" : query);
        }
        if (isAmbiguous(candidates)) {
            return Map.of("status", "CLARIFICATION_REQUIRED", "query", query,
                    "choices", candidates.stream().map(this::placeChoice).toList());
        }

        PlaceCandidate selected = candidates.get(0);
        List<Map<String, Object>> nearbyStations = nearestStations(selected);
        if (nearbyStations.isEmpty()) {
            return Map.of("status", "NO_NEARBY_STATION", "place", selected.formatted(),
                    "message", "Không có ga metro đang hoạt động trong bán kính 5 km");
        }
        return Map.of("status", "RESOLVED", "place", selected.formatted(),
                "nearestStations", nearbyStations);
    }

    private boolean isAmbiguous(List<PlaceCandidate> candidates) {
        return candidates.get(0).confidence() < 0.8
                || (candidates.size() > 1
                        && candidates.get(0).confidence() - candidates.get(1).confidence() <= AMBIGUOUS_CONFIDENCE_GAP);
    }

    private Map<String, Object> placeChoice(PlaceCandidate candidate) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("name", candidate.name());
        choice.put("address", candidate.formatted());
        choice.put("nearestStations", nearestStations(candidate));
        return choice;
    }

    private List<Map<String, Object>> nearestStations(PlaceCandidate place) {
        return stations.findByStatus(StationStatus.ACTIVE).stream()
                .map(station -> Map.entry(station, distanceMeters(
                        place.latitude().doubleValue(), place.longitude().doubleValue(),
                        station.getLatitude().doubleValue(), station.getLongitude().doubleValue())))
                .filter(entry -> entry.getValue() <= MAX_STATION_DISTANCE_METERS)
                .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                .limit(3)
                .map(entry -> {
                    Map<String, Object> station = new LinkedHashMap<>();
                    station.put("name", entry.getKey().getNameStation());
                    station.put("code", entry.getKey().getCode());
                    station.put("distanceMeters", Math.round(entry.getValue()));
                    if (entry.getKey().getDistrict() != null) {
                        station.put("district", entry.getKey().getDistrict());
                    }
                    return station;
                })
                .toList();
    }

    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
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
