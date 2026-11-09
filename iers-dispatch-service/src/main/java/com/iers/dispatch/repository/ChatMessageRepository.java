package com.iers.dispatch.repository;

import com.iers.dispatch.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findByIncidentIdOrderByCreatedAtAsc(UUID incidentId);

    /** A resend of the same message (same id made by the phone). */
    Optional<ChatMessage> findByIncidentIdAndClientMessageId(UUID incidentId, String clientMessageId);
}
