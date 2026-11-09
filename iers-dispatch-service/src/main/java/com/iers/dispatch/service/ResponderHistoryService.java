package com.iers.dispatch.service;

import com.iers.dispatch.dto.response.ResponderHistoryItem;
import com.iers.dispatch.entity.DispatchAttempt;
import com.iers.dispatch.entity.Incident;
import com.iers.dispatch.entity.enums.IncidentStatus;
import com.iers.dispatch.repository.DispatchAttemptRepository;
import com.iers.dispatch.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** PHASE 6: every emergency offered to one responder, newest first (one row per incident). */
@Service
@RequiredArgsConstructor
public class ResponderHistoryService {

    private final DispatchAttemptRepository attemptRepository;
    private final IncidentRepository incidentRepository;

    @Value("${dispatch.give-up-minutes:30}")
    private long giveUpMinutes = 30;

    public List<ResponderHistoryItem> history(UUID responderId) {
        // Newest attempt per incident (the list is newest first, so the first one wins).
        Map<UUID, DispatchAttempt> latest = new LinkedHashMap<>();
        for (DispatchAttempt a : attemptRepository.findTop100ByResponderIdOrderBySentAtDesc(responderId)) {
            latest.putIfAbsent(a.getIncidentId(), a);
        }
        if (latest.isEmpty()) {
            return List.of();
        }
        Map<UUID, Incident> incidents = incidentRepository.findAllById(latest.keySet()).stream()
                .collect(Collectors.toMap(Incident::getId, Function.identity()));

        Instant tooOld = Instant.now().minus(giveUpMinutes, ChronoUnit.MINUTES);
        List<ResponderHistoryItem> rows = new ArrayList<>();
        for (Map.Entry<UUID, DispatchAttempt> e : latest.entrySet()) {
            Incident i = incidents.get(e.getKey());
            if (i == null) {
                continue;
            }
            DispatchAttempt a = e.getValue();
            boolean mine = responderId.equals(i.getAssignedResponderId());
            boolean open = i.getStatus() == IncidentStatus.DISPATCHING
                    || (i.getStatus() == IncidentStatus.DISPATCHED && !mine);
            boolean fresh = i.getCreatedAt() == null || i.getCreatedAt().isAfter(tooOld);
            rows.add(new ResponderHistoryItem(
                    i.getId().toString(),
                    i.getStatus().name(),
                    a.getResponse() != null ? a.getResponse().name() : null,
                    text(a.getSentAt()),
                    text(a.getRespondedAt()),
                    i.getDriverName(),
                    i.getGpsLat(),
                    i.getGpsLng(),
                    i.getPriorityScore(),
                    mine,
                    open && fresh,
                    text(i.getCreatedAt())));
        }
        return rows;
    }

    private static String text(Instant t) {
        return t != null ? t.toString() : null;
    }
}
