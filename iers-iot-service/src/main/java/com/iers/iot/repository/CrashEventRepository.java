package com.iers.iot.repository;

import com.iers.iot.entity.CrashEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface CrashEventRepository extends JpaRepository<CrashEvent, UUID> {
}
