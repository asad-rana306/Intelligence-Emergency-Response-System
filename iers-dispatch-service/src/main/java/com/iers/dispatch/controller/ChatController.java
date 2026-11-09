package com.iers.dispatch.controller;

import com.iers.dispatch.dto.Viewer;
import com.iers.dispatch.dto.request.SendChatTextRequest;
import com.iers.dispatch.dto.response.ChatMessageResponse;
import com.iers.dispatch.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * PHASE 6: chat of one incident (driver <-> assigned responder).
 *   GET  /api/incidents/{id}/messages                       the whole chat, oldest first
 *   POST /api/incidents/{id}/messages                       {text, clientMessageId}       -> 201
 *   POST /api/incidents/{id}/messages/voice                 form: file, durationMs, clientMessageId -> 201
 *   GET  /api/incidents/{id}/messages/{messageId}/audio     the recording (audio/mp4)
 * 400 empty/too long/too big, 403 not driver or responder, 404 unknown, 409 chat closed.
 */
@RestController
@RequestMapping("/api/incidents/{incidentId}/messages")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @GetMapping
    public List<ChatMessageResponse> list(
            @PathVariable UUID incidentId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader(value = "X-User-Role", defaultValue = "UNKNOWN") String role) {
        return chatService.list(incidentId, new Viewer(userId, role));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChatMessageResponse> sendText(
            @PathVariable UUID incidentId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader(value = "X-User-Role", defaultValue = "UNKNOWN") String role,
            @RequestBody SendChatTextRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(chatService.sendText(
                incidentId, new Viewer(userId, role), request.getText(), request.getClientMessageId()));
    }

    @PostMapping(value = "/voice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ChatMessageResponse> sendVoice(
            @PathVariable UUID incidentId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader(value = "X-User-Role", defaultValue = "UNKNOWN") String role,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "durationMs", required = false) Integer durationMs,
            @RequestParam(value = "clientMessageId", required = false) String clientMessageId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(chatService.sendVoice(
                incidentId, new Viewer(userId, role), file, durationMs, clientMessageId));
    }

    @GetMapping("/{messageId}/audio")
    public ResponseEntity<byte[]> audio(
            @PathVariable UUID incidentId,
            @PathVariable UUID messageId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader(value = "X-User-Role", defaultValue = "UNKNOWN") String role) {
        ChatService.Audio audio = chatService.loadAudio(incidentId, messageId, new Viewer(userId, role));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(audio.contentType()))
                .body(audio.bytes());
    }
}
