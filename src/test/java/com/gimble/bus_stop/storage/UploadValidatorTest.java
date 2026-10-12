package com.gimble.bus_stop.storage;

import com.gimble.bus_stop.exceptions.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadValidatorTest {

    @Test
    void detectsByMagicBytesNotClaimedType() {
        var pdf = new MockMultipartFile("file", "a.png", "image/png", "%PDF-1.4 ...".getBytes());
        assertThat(UploadValidator.detect(pdf).contentType()).isEqualTo("application/pdf");

        var png = new MockMultipartFile("file", "a.pdf", "application/pdf",
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A});
        assertThat(UploadValidator.detect(png).extension()).isEqualTo("png");

        var jpeg = new MockMultipartFile("file", "a", null, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00});
        assertThat(UploadValidator.detect(jpeg).contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void rejectsOtherFilesAndEmptyUploads() {
        var html = new MockMultipartFile("file", "a.pdf", "application/pdf", "<html>".getBytes());
        assertThatThrownBy(() -> UploadValidator.detect(html)).isInstanceOf(BusinessException.class);
        var empty = new MockMultipartFile("file", new byte[0]);
        assertThatThrownBy(() -> UploadValidator.detect(empty)).isInstanceOf(BusinessException.class);
    }
}
