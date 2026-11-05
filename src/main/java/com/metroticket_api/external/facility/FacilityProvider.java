package com.metroticket_api.external.facility;

import java.math.BigDecimal;
import java.util.List;

public interface FacilityProvider {
    /**
     * Trả về các tiện ích (cafe, nhà thuốc, trường học, trạm xe buýt, công viên...) quanh tọa độ cho trước.
     * Nguồn dữ liệu khuyến nghị: OpenStreetMap/Overpass.
     */
    List<OsmFeature> getNearbyFacilities(BigDecimal lat, BigDecimal lng, int radiusMeters);
}
