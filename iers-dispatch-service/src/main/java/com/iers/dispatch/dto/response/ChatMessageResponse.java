package com.iers.dispatch.dto.response;

import com.iers.dispatch.entity.ChatMessage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PHASE 6: one chat message as the phones see it (REST and the CHAT_MESSAGE live event).
 * audioUrl is set for VOICE: GET it with the normal login to download the recording.
 */
public record ChatMessageResponse(String messageId,
                                  String incidentId,
                                  String senderId,
                                  String senderRole,
                                  String kind,
                                  String text,
                                  Integer durationMs,
                                  String clientMessageId,
                                  String createdAt,
                                  String audioUrl) {

    public static ChatMessageResponse of(ChatMessage m) {
        String id = m.getId() != null ? m.getId().toString() : null;
        String incident = m.getIncidentId().toString();
        String audioUrl = ChatMessage.VOICE.equals(m.getKind()) && id != null
                ? "/api/incidents/" + incident + "/messages/" + id + "/audio"
                : null;
        return new ChatMessageResponse(id, incident, m.getSenderId().toString(), m.getSenderRole(),
                m.getKind(), m.getText(), m.getDurationMs(), m.getClientMessageId(),
                m.getCreatedAt() != null ? m.getCreatedAt().toString() : null, audioUrl);
    }

    /** The same fields as a map, for the Kafka event (null values allowed). */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("messageId", messageId);
        map.put("incidentId", incidentId);
        map.put("senderId", senderId);
        map.put("senderRole", senderRole);
        map.put("kind", kind);
        map.put("text", text);
        map.put("durationMs", durationMs);
        map.put("clientMessageId", clientMessageId);
        map.put("createdAt", createdAt);
        map.put("audioUrl", audioUrl);
        return map;
    }
}
