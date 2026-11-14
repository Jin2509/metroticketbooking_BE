package com.metroticket_api.ticket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

/** Vẽ nội dung (qrToken của vé) thành ảnh PNG để app hiển thị cho nhân viên quét. */
public final class QrCodeRenderer {

    public static final int MIN_SIZE = 128;
    public static final int MAX_SIZE = 1024;
    public static final int DEFAULT_SIZE = 320;

    private QrCodeRenderer() {
    }

    /** @param size cạnh ảnh (px), bị kẹp trong [MIN_SIZE, MAX_SIZE] */
    public static byte[] png(String content, int size) {
        int side = Math.clamp(size, MIN_SIZE, MAX_SIZE);
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, side, side,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2,
                            EncodeHintType.CHARACTER_SET, "UTF-8"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (WriterException e) {
            throw new IllegalStateException("Không tạo được mã QR", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
