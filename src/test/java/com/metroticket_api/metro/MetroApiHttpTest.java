package com.metroticket_api.metro;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.metroticket_api.TestcontainersConfiguration;
import com.metroticket_api.security.RoleAuthoritiesConverter;
import com.metroticket_api.security.TokenService;

/** Kiểm tra qua HTTP: API công khai không cần token, các thao tác ghi bị chặn đúng quyền, lỗi nghiệp vụ trả đúng mã. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MetroApiHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    RoleAuthoritiesConverter converter;

    private RequestPostProcessor as(String... roles) {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString()).claim(TokenService.ROLES_CLAIM, List.of(roles)))
                .authorities(converter);
    }

    @Test
    void publicEndpointsNeedNoToken() throws Exception {
        mvc.perform(get("/api/v1/public/map/network"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[?(@.code=='BTS-01')].stationCount").value(14));
        mvc.perform(get("/api/v1/public/ticket-products")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='DAY_1')].priceVnd").value(40000));
        mvc.perform(get("/api/v1/public/parking-lots")).andExpect(status().isOk());
        // token hết hạn/không hợp lệ gửi kèm không làm hỏng API công khai
        mvc.perform(get("/api/v1/public/ticket-products").header("Authorization", "Bearer invalid.token"))
                .andExpect(status().isOk());
        // báo giá vé lượt theo quãng đường (bậc đầu tiên)
        mvc.perform(get("/api/v1/public/fares/quote").param("fromStationId", "1").param("toStationId", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.priceVnd").value(7000));
    }

    @Test
    void routeBetweenUnknownStationsIs404AndSameStationIs400() throws Exception {
        mvc.perform(get("/api/v1/public/map/route").param("fromStationId", "999999").param("toStationId", "999998"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/map/route").param("fromStationId", "1").param("toStationId", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void writeEndpointsRequirePermissions() throws Exception {
        String train = "{\"code\":\"TR-HTTP\",\"capacity\":400}";
        mvc.perform(post("/api/v1/trains").contentType(MediaType.APPLICATION_JSON).content(train))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/trains").with(as("PASSENGER")).contentType(MediaType.APPLICATION_JSON).content(train))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/trains").with(as("MANAGER")).contentType(MediaType.APPLICATION_JSON).content(train))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/trains").with(as("ADMIN")).contentType(MediaType.APPLICATION_JSON).content(train))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/trains").with(as("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"TR-BAD\",\"capacity\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/operation-configs/1/approve").with(as("STAFF"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/gates/validate").with(as("PASSENGER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrToken\":\"x\",\"stationId\":1,\"direction\":\"ENTRY\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/parking/check-in").with(as("PASSENGER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lotId\":1,\"vehicleType\":\"BICYCLE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffNotAssignedToStationCannotValidate() throws Exception {
        mvc.perform(post("/api/v1/gates/validate").with(as("STAFF")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrToken\":\"x\",\"stationId\":1,\"direction\":\"ENTRY\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidTrainTransitionIsConflict() throws Exception {
        // TR-006 seed ở trạng thái MAINTENANCE → không thể IN_OPERATION trực tiếp
        String id = mvc.perform(get("/api/v1/trains").param("q", "TR-006").with(as("ADMIN")))
                .andReturn().getResponse().getContentAsString().replaceAll("(?s).*?\"id\":(\\d+).*", "$1");
        mvc.perform(patch("/api/v1/trains/" + id + "/status").with(as("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("\"IN_OPERATION\""))
                .andExpect(status().isConflict());
    }
}
