package com.iers.iot.service;

import com.iers.iot.entity.BystanderMedia;
import com.iers.iot.entity.enums.UploadMediaType;
import com.iers.iot.exception.CrashEventNotFoundException;
import com.iers.iot.repository.BystanderMediaRepository;
import com.iers.iot.repository.CrashEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BystanderMediaService {

    private final BystanderMediaRepository mediaRepository;
    private final CrashEventRepository crashEventRepository;

    @Value("${iot.media.upload-dir:/tmp/iers-uploads}")
    private String uploadDir;

    public BystanderMedia uploadMedia(UUID crashEventId, MultipartFile file,
                                      UploadMediaType type, String description) {
        if (!crashEventRepository.existsById(crashEventId)) {
            throw new CrashEventNotFoundException("Crash event not found: " + crashEventId);
        }

        try {
            Path dir = Paths.get(uploadDir, crashEventId.toString());
            Files.createDirectories(dir);

            String filename = UUID.randomUUID() + "_" + file.getOriginalFilename();
            Path filePath = dir.resolve(filename);
            file.transferTo(filePath.toFile());

            BystanderMedia media = BystanderMedia.builder()
                    .crashEventId(crashEventId)
                    .mediaType(type)
                    .filePath(filePath.toString())
                    .uploadedByDescription(description)
                    .build();

            media = mediaRepository.save(media);
            log.info("Bystander media uploaded: crashEvent={}, type={}, file={}",
                    crashEventId, type, filename);
            return media;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store uploaded file", e);
        }
    }
}
