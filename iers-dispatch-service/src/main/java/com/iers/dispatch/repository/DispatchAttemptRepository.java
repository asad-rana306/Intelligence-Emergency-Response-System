package com.iers.dispatch.repository;

import com.iers.dispatch.entity.DispatchAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface DispatchAttemptRepository extends JpaRepository<DispatchAttempt, UUID> {
    List<DispatchAttempt> findByIncidentIdOrderByAttemptNumber(UUID incidentId);
    int countByIncidentId(UUID incidentId);
}
