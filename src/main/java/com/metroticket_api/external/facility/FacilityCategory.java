package com.metroticket_api.external.facility;

/**
 * Phân loại tiện ích xung quanh ga Metro.
 *
 * <p>Danh mục này dùng để group các kết quả từ Geoapify theo loại tiện ích, giúp frontend
 * hiển thị dễ hơn và cho phép lọc theo từng nhóm (ăn uống, y tế, giáo dục, mua sắm...). </p>
 */
public enum FacilityCategory {
    FOOD,
    HEALTH,
    EDUCATION,
    SHOP,
    PARK,
    BANK,
    TRANSPORT,
    ENTERTAINMENT,
    OTHER
}
