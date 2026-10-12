package com.gimble.bus_stop.controllers;

import com.gimble.bus_stop.services.DriverService.StoredFile;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Serves private files: never cached, never sniffed. */
final class Files {

    private Files() {
    }

    static ResponseEntity<?> inline(StoredFile file) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(file.filename()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(file.resource());
    }
}
