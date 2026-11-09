package com.iers.dispatch.repository;

import com.iers.dispatch.entity.DispatchAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface DispatchAttemptRepository extends JpaRepository<DispatchAttempt, UUID> {
    List<DispatchAttempt> findByIncidentIdOrderByAttemptNumber(UUID incidentId);
    int countByIncidentId(UUID incidentId);

    /** Has this responder ever been offered this incident? (Tells "offer expired" 409 from "not yours" 403.) */
    boolean existsByIncidentIdAndResponderId(UUID incidentId, UUID responderId);

    /** PHASE 6: this responder's newest offers (their history screen). */
    List<DispatchAttempt> findTop100ByResponderIdOrderBySentAtDesc(UUID responderId);
}
