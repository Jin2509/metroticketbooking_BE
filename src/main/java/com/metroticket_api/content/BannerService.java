package com.metroticket_api.content;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.content.ObjectStorage.StoredObject;

import tools.jackson.databind.ObjectMapper;

/**
 * Banner động ở Trang chủ. Ảnh và danh sách (manifest.json) đều nằm trên object storage, không có
 * bảng DB: chưa cấu hình storage thì danh sách rỗng và app dùng ảnh có sẵn.
 */
@Service
public class BannerService {

    private static final Logger log = LoggerFactory.getLogger(BannerService.class);
    private static final String PREFIX = "banners/";
    private static final String MANIFEST = PREFIX + "manifest.json";
    static final int MAX_BYTES = 5 * 1024 * 1024;
    /** Chỉ cho mở màn trong app, không mở liên kết ngoài từ dữ liệu quản trị nhập. */
    private static final Pattern LINK = Pattern.compile("^/p/[A-Za-z0-9/_?=&.-]*$");

    private final ObjectProvider<ObjectStorage> storage;
    private final ObjectMapper mapper;

    public BannerService(ObjectProvider<ObjectStorage> storage, ObjectMapper mapper) {
        this.storage = storage;
        this.mapper = mapper;
    }

    /** Lỗi storage không được làm hỏng Trang chủ: trả rỗng để app dùng ảnh có sẵn. */
    public List<Banner> list() {
        ObjectStorage s = storage.getIfAvailable();
        if (s == null) {
            return List.of();
        }
        try {
            return read(s);
        } catch (RuntimeException e) {
            log.warn("Không đọc được danh sách banner: {}", e.getMessage());
            return List.of();
        }
    }

    public Optional<StoredObject> image(String id) {
        // id đi vào key của storage nên chỉ nhận id có trong danh sách
        return list().stream().filter(b -> b.id().equals(id)).findFirst()
                .flatMap(b -> required().get(PREFIX + b.id()));
    }

    public synchronized Banner add(byte[] image, String title, String subtitle, String linkPath, int position) {
        String contentType = detectImageType(image);
        if (title == null || title.isBlank() || title.length() > 80) {
            throw badRequest("Tiêu đề cần từ 1 đến 80 ký tự");
        }
        if (subtitle != null && subtitle.length() > 160) {
            throw badRequest("Mô tả tối đa 160 ký tự");
        }
        if (linkPath != null && !linkPath.isBlank() && !LINK.matcher(linkPath).matches()) {
            throw badRequest("Liên kết phải là màn trong app, bắt đầu bằng /p/");
        }
        ObjectStorage s = required();
        Banner banner = new Banner(UUID.randomUUID().toString().toLowerCase(Locale.ROOT), title.trim(),
                subtitle == null || subtitle.isBlank() ? null : subtitle.trim(),
                linkPath == null || linkPath.isBlank() ? null : linkPath, position);
        s.put(PREFIX + banner.id(), image, contentType);
        List<Banner> banners = new ArrayList<>(read(s));
        banners.add(banner);
        write(s, banners);
        return banner;
    }

    public synchronized void delete(String id) {
        ObjectStorage s = required();
        List<Banner> banners = new ArrayList<>(read(s));
        if (!banners.removeIf(b -> b.id().equals(id))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy banner");
        }
        write(s, banners);
        s.delete(PREFIX + id);
    }

    private List<Banner> read(ObjectStorage s) {
        return s.get(MANIFEST)
                .map(o -> Arrays.stream(mapper.readValue(o.content(), Banner[].class))
                        .sorted(Comparator.comparingInt(Banner::position))
                        .toList())
                .orElse(List.of());
    }

    private void write(ObjectStorage s, List<Banner> banners) {
        s.put(MANIFEST, mapper.writeValueAsBytes(banners), "application/json");
    }

    private ObjectStorage required() {
        ObjectStorage s = storage.getIfAvailable();
        if (s == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa cấu hình lưu trữ ảnh");
        }
        return s;
    }

    /** Xét theo nội dung file, không tin Content-Type do client gửi. */
    private static String detectImageType(byte[] b) {
        if (b == null || b.length == 0 || b.length > MAX_BYTES) {
            throw badRequest("Ảnh phải có dung lượng từ 1 byte đến 5 MB");
        }
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        throw badRequest("Chỉ nhận ảnh JPEG, PNG hoặc WebP");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record Banner(String id, String title, String subtitle, String linkPath, int position) {
    }
}
