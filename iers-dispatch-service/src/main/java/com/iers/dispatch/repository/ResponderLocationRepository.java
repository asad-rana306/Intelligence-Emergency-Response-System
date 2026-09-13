package com.iers.dispatch.repository;

import com.iers.dispatch.entity.ResponderLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResponderLocationRepository extends JpaRepository<ResponderLocation, UUID> {

    Optional<ResponderLocation> findByResponderId(UUID responderId);

    /**
     * PostGIS spatial query: find nearest ON_DUTY responders by real-world distance.
     * Uses ST_DistanceSphere for accurate great-circle distance calculation.
     * Excludes responders already attempted for this incident.
     *
     * Falls back to Euclidean approx on H2 (tests) — PostGIS functions only on Postgres.
     */
    @Query(value = """
            SELECT * FROM responder_locations
            WHERE duty_status = 'ON_DUTY'
              AND latitude IS NOT NULL
              AND longitude IS NOT NULL
              AND responder_id NOT IN (:excludedIds)
            ORDER BY ST_DistanceSphere(
                ST_MakePoint(longitude, latitude),
                ST_MakePoint(:lng, :lat)
            )
            LIMIT :limit
            """, nativeQuery = true)
    List<ResponderLocation> findNearestResponders(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("excludedIds") List<UUID> excludedIds,
            @Param("limit") int limit);

    /**
     * Simple fallback query when no exclusions exist (avoids empty IN clause).
     */
    @Query(value = """
            SELECT * FROM responder_locations
            WHERE duty_status = 'ON_DUTY'
              AND latitude IS NOT NULL
              AND longitude IS NOT NULL
            ORDER BY ST_DistanceSphere(
                ST_MakePoint(longitude, latitude),
                ST_MakePoint(:lng, :lat)
            )
            LIMIT :limit
            """, nativeQuery = true)
    List<ResponderLocation> findNearestRespondersNoExclusion(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("limit") int limit);
}
