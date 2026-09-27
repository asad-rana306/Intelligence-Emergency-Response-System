package com.iers.iot.repository;

import com.iers.iot.entity.BystanderMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface BystanderMediaRepository extends JpaRepository<BystanderMedia, UUID> {
    List<BystanderMedia> findAllByCrashEventId(UUID crashEventId);
}
