package com.iers.iot.repository;

import com.iers.iot.entity.CrashEvent;
import com.iers.iot.entity.enums.CrashEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CrashEventRepository extends JpaRepository<CrashEvent, UUID> {

    /** The device's newest crash (used by the box's cancel button). */
    Optional<CrashEvent> findFirstByDeviceIdOrderByCreatedAtDesc(String deviceId);

    /** Crashes still waiting in the window whose deadline already passed (used by the sweep). */
    List<CrashEvent> findByStatusAndCancellationDeadlineBefore(CrashEventStatus status, Instant deadline);

    /**
     * Move a crash from one status to another ONLY if it is still in the "from" status.
     * Returns 1 if this caller won, 0 if someone else got there first. This is what stops the
     * timer, the sweep and a cancel from handling the same crash twice.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update CrashEvent e set e.status = :toStatus where e.id = :id and e.status = :fromStatus")
    int transition(@Param("id") UUID id,
                   @Param("fromStatus") CrashEventStatus from,
                   @Param("toStatus") CrashEventStatus to);
}

