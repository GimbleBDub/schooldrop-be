package com.gimble.bus_stop.storage;

import com.gimble.bus_stop.exceptions.BusinessException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Accepts only PDF/JPEG/PNG, judged by the file's magic bytes rather than the client's content type. */
public final class UploadValidator {

    public record DetectedType(String contentType, String extension) {
    }

    private static final byte[] PDF = {0x25, 0x50, 0x44, 0x46};             // %PDF
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47};

    private UploadValidator() {
    }

    public static DetectedType detect(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("File is empty");
        }
        byte[] header = new byte[4];
        try (InputStream in = file.getInputStream()) {
            if (in.readNBytes(header, 0, 4) < 3) {
                throw BusinessException.badRequest("File is not a valid PDF, JPEG or PNG");
            }
        } catch (IOException e) {
            throw BusinessException.badRequest("Could not read the uploaded file");
        }
        if (startsWith(header, PDF)) {
            return new DetectedType("application/pdf", "pdf");
        }
        if (startsWith(header, JPEG)) {
            return new DetectedType("image/jpeg", "jpg");
        }
        if (startsWith(header, PNG)) {
            return new DetectedType("image/png", "png");
        }
        throw BusinessException.badRequest("Only PDF, JPEG or PNG files are accepted");
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }
}
