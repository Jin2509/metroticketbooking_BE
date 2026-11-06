/**
 * Gói metro chứa toàn bộ mô-đun quản lý tuyến, ga, tàu, cấu hình vận hành và thông báo liên quan đến hệ thống Metro.
 *
 * <p>Những chức năng chính đã triển khai:</p>
 * <ul>
 *   <li>Quản lý tuyến Metro: tạo, cập nhật, xóa, tìm kiếm và xem danh sách tuyến.</li>
 *   <li>Quản lý nhà ga: thêm/sửa/xóa ga, tìm theo tuyến, tìm gần vị trí, xem kết nối và tuyến đi qua ga.</li>
 *   <li>Quản lý đoàn tàu: quản lý thông tin tàu, trạng thái vận hành và phân tuyến.</li>
 *   <li>Cấu hình vận hành: lưu phiên bản cấu hình, workflow duyệt và kích hoạt tuyến.</li>
 *   <li>Thông báo tuyến/ga: tạo, gửi duyệt, phê duyệt, từ chối và phát sóng tin nhắn theo tuyến hoặc ga.</li>
 * </ul>
 *
 * <p>Mỗi lớp trong gói đều tách rõ phần Controller, Service, Repository, DTO và Entity để dễ mở rộng,
 * test và duy trì theo nguyên tắc clean code.</p>
 */
package com.metroticket_api.metro;
