package com.iers.dispatch.repository;

import com.iers.dispatch.entity.Incident;
import com.iers.dispatch.entity.enums.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {
    Optional<Incident> findByCrashEventId(UUID crashEventId);
    List<Incident> findByStatusIn(List<IncidentStatus> statuses);
}
