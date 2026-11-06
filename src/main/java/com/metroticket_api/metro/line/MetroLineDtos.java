package com.metroticket_api.metro.line;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.metroticket_api.metro.common.MetroLineStatus;

public class MetroLineDtos {

    /** Mã màu dạng #RRGGBB; cho phép chuỗi rỗng ở request cập nhật để xóa màu. */
    static final String COLOR_HEX = "^(#[0-9A-Fa-f]{6})?$";

    public record CreateLineRequest(
            @NotBlank @Size(max = 20) String code,
            @NotBlank @Size(max = 100) String nameLine,
            @Pattern(regexp = COLOR_HEX, message = "colorHex phải có dạng #RRGGBB") String colorHex,
            String description,
            MetroLineStatus status) {
    }

    public record UpdateLineRequest(
            @Size(max = 20) String code,
            @Size(max = 100) String nameLine,
            @Pattern(regexp = COLOR_HEX, message = "colorHex phải có dạng #RRGGBB") String colorHex,
            String description,
            MetroLineStatus status) {
    }

    public record LineResponse(
            Long id,
            String code,
            String nameLine,
            String colorHex,
            String description,
            MetroLineStatus status,
            Instant createdAt,
            Instant updatedAt) {
        public static LineResponse of(MetroLine line) {
            return new LineResponse(
                    line.getId(),
                    line.getCode(),
                    line.getNameLine(),
                    line.getColorHex(),
                    line.getDescription(),
                    line.getStatus(),
                    line.getCreatedAt(),
                    line.getUpdatedAt());
        }
    }
}
