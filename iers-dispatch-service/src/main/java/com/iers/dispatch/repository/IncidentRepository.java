package com.iers.dispatch.repository;

import com.iers.dispatch.entity.Incident;
import com.iers.dispatch.entity.enums.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every method below that changes a status is ONE conditional UPDATE ("do this only if the
 * incident is still in state X"). It returns how many rows changed: 1 = you won, 0 = somebody
 * else got there first (timer, sweep, driver cancel, another phone). That is what stops two
 * threads from handling the same offer twice, and what lets a cancel always win.
 *
 * (Parameter names avoid the JPQL words FROM and TO on purpose.)
 */
public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    // ── Plain reads ──

    Optional<Incident> findByCrashEventId(UUID crashEventId);

    List<Incident> findByStatusIn(Collection<IncidentStatus> statuses);

    /** Incidents where this person is the driver OR the offered/assigned responder. */
    @Query("select i from Incident i where i.status in :statuses "
            + "and (i.driverId = :userId or i.assignedResponderId = :userId) "
            + "order by i.createdAt desc")
    List<Incident> findForUser(@Param("statuses") Collection<IncidentStatus> statuses,
                               @Param("userId") UUID userId);

    Optional<Incident> findFirstByDriverIdAndStatusInOrderByCreatedAtDesc(
            UUID driverId, Collection<IncidentStatus> statuses);

    Optional<Incident> findFirstByAssignedResponderIdAndStatusInOrderByCreatedAtDesc(
            UUID responderId, Collection<IncidentStatus> statuses);

    /** Offers whose 30 seconds are over (the sweep uses this when the in-memory timer was lost). */
    List<Incident> findByStatusAndDispatchExpiresAtBefore(IncidentStatus status, Instant cutoff);

    /** Incidents waiting for another try at finding a responder. */
    List<Incident> findByStatusAndNextRetryAtBefore(IncidentStatus status, Instant cutoff);

    // ── Conditional status changes ──

    /** DISPATCHING -> DISPATCHED: hand the incident to one responder for a limited time. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Incident i set i.status = :toStatus, i.assignedResponderId = :responderId, "
            + "i.responderName = :name, i.responderPhone = :phone, i.responderVehicleId = :vehicleId, "
            + "i.dispatchExpiresAt = :expiresAt, i.nextRetryAt = null "
            + "where i.id = :id and i.status = :fromStatus")
    int claimOffer(@Param("id") UUID id,
                   @Param("fromStatus") IncidentStatus fromStatus,
                   @Param("toStatus") IncidentStatus toStatus,
                   @Param("responderId") UUID responderId,
                   @Param("name") String name,
                   @Param("phone") String phone,
                   @Param("vehicleId") String vehicleId,
                   @Param("expiresAt") Instant expiresAt);

    /** DISPATCHED -> DISPATCHING, only if THIS responder still holds the offer (reject or time-out). */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Incident i set i.status = :toStatus, i.assignedResponderId = null, "
            + "i.responderName = null, i.responderPhone = null, i.responderVehicleId = null, "
            + "i.dispatchExpiresAt = null, i.nextRetryAt = :retryAt "
            + "where i.id = :id and i.status = :fromStatus and i.assignedResponderId = :responderId")
    int releaseOffer(@Param("id") UUID id,
                     @Param("responderId") UUID responderId,
                     @Param("fromStatus") IncidentStatus fromStatus,
                     @Param("toStatus") IncidentStatus toStatus,
                     @Param("retryAt") Instant retryAt);

    /** DISPATCHED -> ACCEPTED, only for the responder who holds the offer. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Incident i set i.status = :toStatus, i.acceptedAt = :changedAt, "
            + "i.dispatchExpiresAt = null, i.nextRetryAt = null "
            + "where i.id = :id and i.status = :fromStatus and i.assignedResponderId = :responderId")
    int acceptOffer(@Param("id") UUID id,
                    @Param("responderId") UUID responderId,
                    @Param("fromStatus") IncidentStatus fromStatus,
                    @Param("toStatus") IncidentStatus toStatus,
                    @Param("changedAt") Instant at);

    /** ACCEPTED -> ARRIVED, only for the assigned responder. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Incident i set i.status = :toStatus, i.arrivedAt = :changedAt "
            + "where i.id = :id and i.status = :fromStatus and i.assignedResponderId = :responderId")
    int markArrived(@Param("id") UUID id,
                    @Param("responderId") UUID responderId,
                    @Param("fromStatus") IncidentStatus fromStatus,
                    @Param("toStatus") IncidentStatus toStatus,
                    @Param("changedAt") Instant at);

    /** ARRIVED -> EN_ROUTE_HOSPITAL, only for the assigned responder. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Incident i set i.status = :toStatus, i.enRouteAt = :changedAt, i.hospitalId = :hospitalId "
            + "where i.id = :id and i.status = :fromStatus and i.assignedResponderId = :responderId")
    int markEnRoute(@Param("id") UUID id,
                    @Param("responderId") UUID responderId,
                    @Param("fromStatus") IncidentStatus fromStatus,
                    @Param("toStatus") IncidentStatus toStatus,
                    @Param("changedAt") Instant at,
                    @Param("hospitalId") String hospitalId);

    /** ARRIVED or EN_ROUTE_HOSPITAL -> RESOLVED, only for the assigned responder. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Incident i set i.status = :toStatus, i.resolvedAt = :changedAt "
            + "where i.id = :id and i.status in :fromStatuses and i.assignedResponderId = :responderId")
    int markResolved(@Param("id") UUID id,
                     @Param("responderId") UUID responderId,
                     @Param("fromStatuses") Collection<IncidentStatus> fromStatuses,
                     @Param("toStatus") IncidentStatus toStatus,
                     @Param("changedAt") Instant at);

    /** Any unfinished state -> CANCELLED_BY_USER. Does nothing to an incident that is already over. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Incident i set i.status = :toStatus, i.resolvedAt = :changedAt, "
            + "i.dispatchExpiresAt = null, i.nextRetryAt = null "
            + "where i.id = :id and i.status in :activeStatuses")
    int cancelIfActive(@Param("id") UUID id,
                       @Param("activeStatuses") Collection<IncidentStatus> activeStatuses,
                       @Param("toStatus") IncidentStatus toStatus,
                       @Param("changedAt") Instant at);

    // ── Small flags ──

    /** Remember when to try again (only while still DISPATCHING). */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Incident i set i.nextRetryAt = :retryAt where i.id = :id and i.status = :status")
    int scheduleRetry(@Param("id") UUID id,
                      @Param("status") IncidentStatus status,
                      @Param("retryAt") Instant retryAt);

    /** Stop retrying (the incident is too old). */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Incident i set i.nextRetryAt = null where i.id = :id and i.status = :status")
    int stopRetrying(@Param("id") UUID id, @Param("status") IncidentStatus status);

    /** First time only: 1 = announce NO_RESPONDER_AVAILABLE now, 0 = already announced. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Incident i set i.noResponderNotified = true "
            + "where i.id = :id and i.status = :status "
            + "and (i.noResponderNotified is null or i.noResponderNotified = false)")
    int markNoResponderNotified(@Param("id") UUID id, @Param("status") IncidentStatus status);

    /** First tap only: 1 = tell the responder, 0 = a bystander was already marked. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Incident i set i.bystanderAssisting = true "
            + "where i.id = :id and i.status in :activeStatuses "
            + "and (i.bystanderAssisting is null or i.bystanderAssisting = false)")
    int markBystanderAssisting(@Param("id") UUID id,
                               @Param("activeStatuses") Collection<IncidentStatus> activeStatuses);
}
