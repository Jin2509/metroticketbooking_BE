package com.metroticket_api.content;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.content.BannerService.Banner;

/** Đọc công khai vì app tải ảnh không kèm token. Ghi chỉ dành cho ADMIN. */
@RestController
@RequestMapping("/api/content/banners")
public class BannerController {

    private final BannerService banners;

    public BannerController(BannerService banners) {
        this.banners = banners;
    }

    @GetMapping
    public List<BannerResponse> list() {
        return banners.list().stream().map(BannerResponse::of).toList();
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable String id) {
        return banners.image(id)
                .map(o -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(o.contentType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                        .body(o.content()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ảnh"));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public BannerResponse add(@RequestParam MultipartFile image, @RequestParam String title,
            @RequestParam(required = false) String subtitle,
            @RequestParam(required = false) String linkPath,
            @RequestParam(defaultValue = "0") int position) throws IOException {
        return BannerResponse.of(banners.add(image.getBytes(), title, subtitle, linkPath, position));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        banners.delete(id);
    }

    public record BannerResponse(String id, String title, String subtitle, String linkPath, String imagePath) {
        static BannerResponse of(Banner b) {
            return new BannerResponse(b.id(), b.title(), b.subtitle(), b.linkPath(),
                    "/api/content/banners/" + b.id() + "/image");
        }
    }
}
