package com.iers.dispatch.dto.response;

/**
 * PHASE 6: one row of the responder's history (GET /api/dispatch/history).
 * myResponse: PENDING, ACCEPTED, REJECTED or TIMED_OUT (this responder's last answer).
 * canAcceptAgain: nobody has accepted it yet, so POST /api/dispatch/{id}/reaccept will work.
 * Medical data is not included: it is shown only to the offered or assigned responder.
 */
public record ResponderHistoryItem(String incidentId,
                                   String status,
                                   String myResponse,
                                   String offeredAt,
                                   String respondedAt,
                                   String driverName,
                                   Double gpsLat,
                                   Double gpsLng,
                                   Integer priorityScore,
                                   boolean assignedToMe,
                                   boolean canAcceptAgain,
                                   String createdAt) { }
