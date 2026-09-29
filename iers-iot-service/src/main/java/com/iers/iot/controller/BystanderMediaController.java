package com.iers.iot.controller;

import com.iers.iot.entity.BystanderMedia;
import com.iers.iot.entity.enums.UploadMediaType;
import com.iers.iot.service.BystanderMediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/telemetry/crash")
@RequiredArgsConstructor
public class BystanderMediaController {

    private final BystanderMediaService mediaService;

    @PostMapping("/{crashEventId}/media")
    public ResponseEntity<Map<String, String>> uploadMedia(
            @PathVariable UUID crashEventId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("type") UploadMediaType type,
            @RequestParam(value = "description", required = false) String description) {

        BystanderMedia media = mediaService.uploadMedia(crashEventId, file, type, description);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "mediaId", media.getId().toString(),
                "status", "UPLOADED"
        ));
    }
}
