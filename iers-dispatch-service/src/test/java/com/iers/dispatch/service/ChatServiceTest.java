package com.iers.dispatch.service;

import com.iers.dispatch.dto.Viewer;
import com.iers.dispatch.dto.response.ChatMessageResponse;
import com.iers.dispatch.entity.ChatMessage;
import com.iers.dispatch.entity.Incident;
import com.iers.dispatch.entity.enums.IncidentStatus;
import com.iers.dispatch.exception.IncidentAccessDeniedException;
import com.iers.dispatch.exception.InvalidIncidentStateException;
import com.iers.dispatch.kafka.IncidentEventPublisher;
import com.iers.dispatch.repository.ChatMessageRepository;
import com.iers.dispatch.repository.IncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private ChatMessageRepository chatRepository;
    @Mock private IncidentEventPublisher eventPublisher;

    @InjectMocks private ChatService chatService;

    @TempDir Path storage;

    private final UUID incidentId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private final UUID responderId = UUID.randomUUID();
    private final Viewer driver = new Viewer(driverId, "DRIVER");
    private final Viewer responder = new Viewer(responderId, "RESPONDER");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(chatService, "storageDir", storage.toString());
    }

    private void incident(IncidentStatus status) {
        Incident i = Incident.builder().id(incidentId).crashEventId(UUID.randomUUID())
                .driverId(driverId).assignedResponderId(responderId).status(status)
                .createdAt(Instant.now()).build();
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(i));
    }

    private void saveAssignsId() {
        when(chatRepository.save(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> publishedData() {
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(eventPublisher).publish(eq("CHAT_MESSAGE"), any(Incident.class), eq(responderId), data.capture());
        return data.getValue();
    }

    // ── text ──

    @Test
    @DisplayName("sendText — the driver writes: saved, announced to both as CHAT_MESSAGE")
    void text_driver() {
        incident(IncidentStatus.ACCEPTED);
        saveAssignsId();

        ChatMessageResponse r = chatService.sendText(incidentId, driver, "  I can't move my leg  ", "c-1");

        assertThat(r.senderRole()).isEqualTo("DRIVER");
        assertThat(r.kind()).isEqualTo("TEXT");
        assertThat(r.text()).isEqualTo("I can't move my leg");
        assertThat(r.audioUrl()).isNull();
        assertThat(publishedData()).containsEntry("text", "I can't move my leg").containsEntry("clientMessageId", "c-1");
    }

    @Test
    @DisplayName("sendText — the accepted responder writes")
    void text_responder() {
        incident(IncidentStatus.ARRIVED);
        saveAssignsId();

        ChatMessageResponse r = chatService.sendText(incidentId, responder, "Stay still, I am here", null);

        assertThat(r.senderRole()).isEqualTo("RESPONDER");
        assertThat(publishedData()).containsEntry("senderId", responderId.toString());
    }

    @Test
    @DisplayName("sendText — a responder with only an offer (not accepted) cannot write: 403")
    void text_offerOnly() {
        incident(IncidentStatus.DISPATCHED);

        assertThatThrownBy(() -> chatService.sendText(incidentId, responder, "hi", null))
                .isInstanceOf(IncidentAccessDeniedException.class);
        verify(chatRepository, never()).save(any());
    }

    @Test
    @DisplayName("sendText — a stranger cannot write: 403")
    void text_stranger() {
        incident(IncidentStatus.ACCEPTED);

        assertThatThrownBy(() -> chatService.sendText(incidentId, new Viewer(UUID.randomUUID(), "DRIVER"), "hi", null))
                .isInstanceOf(IncidentAccessDeniedException.class);
    }

    @Test
    @DisplayName("sendText — after RESOLVED the chat is read-only: 409")
    void text_closed() {
        incident(IncidentStatus.RESOLVED);

        assertThatThrownBy(() -> chatService.sendText(incidentId, driver, "thanks", null))
                .isInstanceOf(InvalidIncidentStateException.class);
    }

    @Test
    @DisplayName("sendText — empty text: 400")
    void text_empty() {
        assertThatThrownBy(() -> chatService.sendText(incidentId, driver, "   ", null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(incidentRepository, chatRepository, eventPublisher);
    }

    @Test
    @DisplayName("sendText — too long: 400")
    void text_tooLong() {
        assertThatThrownBy(() -> chatService.sendText(incidentId, driver, "x".repeat(2001), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sendText — a resend with the same clientMessageId is not saved or announced twice")
    void text_resend() {
        incident(IncidentStatus.ACCEPTED);
        ChatMessage saved = ChatMessage.builder().id(UUID.randomUUID()).incidentId(incidentId)
                .senderId(driverId).senderRole("DRIVER").kind("TEXT").text("help").clientMessageId("c-9")
                .createdAt(Instant.now()).build();
        when(chatRepository.findByIncidentIdAndClientMessageId(incidentId, "c-9")).thenReturn(Optional.of(saved));

        ChatMessageResponse r = chatService.sendText(incidentId, driver, "help", "c-9");

        assertThat(r.messageId()).isEqualTo(saved.getId().toString());
        verify(chatRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    // ── voice ──

    @Test
    @DisplayName("sendVoice — the recording is stored on disk and announced with its audioUrl")
    void voice_stored() throws Exception {
        incident(IncidentStatus.ACCEPTED);
        saveAssignsId();
        MockMultipartFile file = new MockMultipartFile("file", "note.m4a", "audio/mp4", new byte[]{1, 2, 3, 4});

        ChatMessageResponse r = chatService.sendVoice(incidentId, driver, file, 4200, "v-1");

        assertThat(r.kind()).isEqualTo("VOICE");
        assertThat(r.durationMs()).isEqualTo(4200);
        assertThat(r.audioUrl()).isEqualTo("/api/incidents/" + incidentId + "/messages/" + r.messageId() + "/audio");
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatRepository).save(captor.capture());
        assertThat(Files.readAllBytes(storage.resolve(captor.getValue().getAudioPath()))).containsExactly(1, 2, 3, 4);
        assertThat(publishedData()).containsEntry("kind", "VOICE");
    }

    @Test
    @DisplayName("sendVoice — bigger than 2 MB: 400, nothing stored")
    void voice_tooBig() {
        MultipartFile big = mock(MultipartFile.class);
        when(big.isEmpty()).thenReturn(false);
        when(big.getSize()).thenReturn(3L * 1024 * 1024);

        assertThatThrownBy(() -> chatService.sendVoice(incidentId, driver, big, 1000, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(chatRepository, eventPublisher);
    }

    @Test
    @DisplayName("loadAudio — returns the stored bytes")
    void voice_download() throws Exception {
        incident(IncidentStatus.RESOLVED);
        UUID messageId = UUID.randomUUID();
        Files.createDirectories(storage.resolve(incidentId.toString()));
        Files.write(storage.resolve(incidentId + "/a.m4a"), new byte[]{9, 8});
        when(chatRepository.findById(messageId)).thenReturn(Optional.of(ChatMessage.builder()
                .id(messageId).incidentId(incidentId).senderId(driverId).senderRole("DRIVER")
                .kind("VOICE").audioPath(incidentId + "/a.m4a").createdAt(Instant.now()).build()));

        ChatService.Audio audio = chatService.loadAudio(incidentId, messageId, responder);

        assertThat(audio.bytes()).containsExactly(9, 8);
        assertThat(audio.contentType()).isEqualTo("audio/mp4");
    }

    // ── read ──

    @Test
    @DisplayName("list — after RESOLVED both sides still read the history")
    void list_afterResolved() {
        incident(IncidentStatus.RESOLVED);
        when(chatRepository.findByIncidentIdOrderByCreatedAtAsc(incidentId)).thenReturn(List.of(
                ChatMessage.builder().id(UUID.randomUUID()).incidentId(incidentId).senderId(driverId)
                        .senderRole("DRIVER").kind("TEXT").text("help").createdAt(Instant.now()).build()));

        assertThat(chatService.list(incidentId, responder)).hasSize(1);
        assertThat(chatService.list(incidentId, driver)).hasSize(1);
    }

    @Test
    @DisplayName("list — a stranger cannot read: 403")
    void list_stranger() {
        incident(IncidentStatus.ACCEPTED);

        assertThatThrownBy(() -> chatService.list(incidentId, new Viewer(UUID.randomUUID(), "RESPONDER")))
                .isInstanceOf(IncidentAccessDeniedException.class);
    }
}
