package com.metroticket_api.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Cấu hình client gọi các API bên ngoài với timeout hợp lý để tránh request treo.
 *
 * <p>Hiện tại project hỗ trợ 2 nhà cung cấp chính:
 * <ul>
 *   <li>Bus: city transit provider hoặc GTFS-backed API, ví dụ http://apicms.ebms.vn/businfo.</li>
 *   <li>Facilities: OpenStreetMap/Overpass, ví dụ https://overpass-api.de/api/interpreter.</li>
 * </ul>
 * Các endpoint và path được cấu hình qua application.properties bằng biến môi trường.
 */
@Configuration
public class ExternalApiConfig {

    @Bean
    @Primary
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    @Bean
    @Qualifier("osmRestTemplate")
    public RestTemplate osmRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(15000);
        return new RestTemplate(factory);
    }
}
