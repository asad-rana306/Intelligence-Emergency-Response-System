package com.iers.iot.entity.enums;

public enum CrashEventStatus {
    RECEIVED,
    PROCESSING,
    CONFIRMED,
    CANCELLED,
    LATE_CANCELLED,
    DISPATCHED,   // reserved, nothing sets it yet
    DISMISSED     // NEW (Phase 2): the ML check said "not severe", so nothing was dispatched
}
