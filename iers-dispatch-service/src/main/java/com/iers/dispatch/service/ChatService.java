package com.iers.dispatch.service;

import com.iers.dispatch.dto.Viewer;
import com.iers.dispatch.dto.kafka.IncidentEventMessage;
import com.iers.dispatch.dto.response.ChatMessageResponse;
import com.iers.dispatch.entity.ChatMessage;
import com.iers.dispatch.entity.Incident;
import com.iers.dispatch.entity.enums.IncidentStatus;
import com.iers.dispatch.exception.IncidentAccessDeniedException;
import com.iers.dispatch.exception.IncidentNotFoundException;
import com.iers.dispatch.exception.InvalidIncidentStateException;
import com.iers.dispatch.kafka.IncidentEventPublisher;
import com.iers.dispatch.repository.ChatMessageRepository;
import com.iers.dispatch.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PHASE 6: chat between the driver and the assigned responder of one incident (text + voice notes).
 *
 * Rules:
 *  - Only the driver and the responder who ACCEPTED can read or write. Admin can read.
 *  - Writing is open while a responder is on the case (ACCEPTED, ARRIVED, EN_ROUTE_HOSPITAL).
 *    Reading stays open afterwards, so both sides keep the history.
 *  - A resend with the same clientMessageId returns the saved message (bad network = no doubles).
 *  - Voice notes: max 2 MB (about 10 minutes at the app's 24 kbps), stored on disk in the chat folder.
 *  - Every new message is announced as CHAT_MESSAGE on incident-events; Realtime pushes it to both.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    static final int MAX_TEXT_LENGTH = 2000;
    static final long MAX_AUDIO_BYTES = 2L * 1024 * 1024;
    static final int MAX_CLIENT_ID_LENGTH = 64;

    private static final List<IncidentStatus> READABLE = List.of(
            IncidentStatus.ACCEPTED, IncidentStatus.ARRIVED, IncidentStatus.EN_ROUTE_HOSPITAL,
            IncidentStatus.RESOLVED, IncidentStatus.CANCELLED_BY_USER);

    private final IncidentRepository incidentRepository;
    private final ChatMessageRepository chatRepository;
    private final IncidentEventPublisher eventPublisher;

    @Value("${iers.chat.storage-dir:./chat-audio}")
    private String storageDir = "./chat-audio";

    /** A downloaded voice note. */
    public record Audio(byte[] bytes, String contentType) { }

    public List<ChatMessageResponse> list(UUID incidentId, Viewer viewer) {
        Incident incident = load(incidentId);
        requireReader(incident, viewer);
        return chatRepository.findByIncidentIdOrderByCreatedAtAsc(incidentId).stream()
                .map(ChatMessageResponse::of)
                .toList();
    }

    @Transactional
    public ChatMessageResponse sendText(UUID incidentId, Viewer viewer, String text, String clientMessageId) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("The message is empty");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("The message is too long (max " + MAX_TEXT_LENGTH + " characters)");
        }
        String clientId = cleanClientId(clientMessageId);
        Incident incident = load(incidentId);
        String role = requireWriter(incident, viewer);

        Optional<ChatMessage> repeat = findRepeat(incidentId, clientId);
        if (repeat.isPresent()) {
            return ChatMessageResponse.of(repeat.get());
        }

        ChatMessage saved = chatRepository.save(ChatMessage.builder()
                .incidentId(incidentId)
                .senderId(viewer.id())
                .senderRole(role)
                .kind(ChatMessage.TEXT)
                .text(text.trim())
                .clientMessageId(clientId)
                .createdAt(now())
                .build());
        return announce(incident, saved);
    }

    @Transactional
    public ChatMessageResponse sendVoice(UUID incidentId, Viewer viewer, MultipartFile file,
                                         Integer durationMs, String clientMessageId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("The voice note is empty");
        }
        if (file.getSize() > MAX_AUDIO_BYTES) {
            throw new IllegalArgumentException("The voice note is too big (max 2 MB)");
        }
        String clientId = cleanClientId(clientMessageId);
        Incident incident = load(incidentId);
        String role = requireWriter(incident, viewer);

        Optional<ChatMessage> repeat = findRepeat(incidentId, clientId);
        if (repeat.isPresent()) {
            return ChatMessageResponse.of(repeat.get());
        }

        String relative = incidentId + "/" + UUID.randomUUID() + ".m4a";
        Path target = Paths.get(storageDir).resolve(relative);
        try (InputStream in = file.getInputStream()) {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Could not store voice note for incident {}: {}", incidentId, e.getMessage());
            throw new IllegalStateException("Could not store the voice note", e);
        }

        ChatMessage saved = chatRepository.save(ChatMessage.builder()
                .incidentId(incidentId)
                .senderId(viewer.id())
                .senderRole(role)
                .kind(ChatMessage.VOICE)
                .audioPath(relative)
                .durationMs(durationMs != null && durationMs > 0 ? durationMs : null)
                .clientMessageId(clientId)
                .createdAt(now())
                .build());
        return announce(incident, saved);
    }

    public Audio loadAudio(UUID incidentId, UUID messageId, Viewer viewer) {
        Incident incident = load(incidentId);
        requireReader(incident, viewer);
        ChatMessage message = chatRepository.findById(messageId)
                .filter(m -> incidentId.equals(m.getIncidentId()) && ChatMessage.VOICE.equals(m.getKind()))
                .orElseThrow(() -> new IncidentNotFoundException("Voice note not found: " + messageId));
        Path file = Paths.get(storageDir).resolve(message.getAudioPath());
        try {
            return new Audio(Files.readAllBytes(file), "audio/mp4");
        } catch (IOException e) {
            throw new IncidentNotFoundException("The voice note file is missing on the server");
        }
    }

    // ── rules ──

    /** Driver of the incident, or the responder who accepted it. Returns DRIVER or RESPONDER. */
    private String requireWriter(Incident incident, Viewer viewer) {
        String role = participantRole(incident, viewer);
        if (role == null) {
            throw new IncidentAccessDeniedException("Only the driver and the responder of this emergency can chat");
        }
        if (!IncidentService.ACCEPTED_AND_ON.contains(incident.getStatus())) {
            throw new InvalidIncidentStateException(
                    "Chat is open only while a responder is on the case (now " + incident.getStatus() + ")");
        }
        return role;
    }

    private void requireReader(Incident incident, Viewer viewer) {
        if (viewer.isAdmin()) {
            return;
        }
        if (participantRole(incident, viewer) == null) {
            throw new IncidentAccessDeniedException("Only the driver and the responder of this emergency can read this chat");
        }
    }

    private String participantRole(Incident incident, Viewer viewer) {
        if (viewer.isDriverOf(incident)) {
            return Viewer.DRIVER;
        }
        // The responder only after accepting (an offer alone does not open the chat).
        if (viewer.isResponderOf(incident) && READABLE.contains(incident.getStatus())) {
            return Viewer.RESPONDER;
        }
        return null;
    }

    // ── helpers ──

    private ChatMessageResponse announce(Incident incident, ChatMessage saved) {
        ChatMessageResponse response = ChatMessageResponse.of(saved);
        eventPublisher.publish(IncidentEventMessage.CHAT_MESSAGE, incident,
                incident.getAssignedResponderId(), response.asMap());
        log.info("Chat {} from {} in incident {}", saved.getKind(), saved.getSenderRole(), incident.getId());
        return response;
    }

    private Optional<ChatMessage> findRepeat(UUID incidentId, String clientId) {
        return clientId == null ? Optional.empty()
                : chatRepository.findByIncidentIdAndClientMessageId(incidentId, clientId);
    }

    private static String cleanClientId(String clientMessageId) {
        if (clientMessageId == null || clientMessageId.isBlank()) {
            return null;
        }
        String trimmed = clientMessageId.trim();
        if (trimmed.length() > MAX_CLIENT_ID_LENGTH) {
            throw new IllegalArgumentException("clientMessageId is too long (max " + MAX_CLIENT_ID_LENGTH + ")");
        }
        return trimmed;
    }

    private Incident load(UUID incidentId) {
        return incidentRepository.findById(incidentId)
                .orElseThrow(() -> new IncidentNotFoundException("Incident not found: " + incidentId));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
