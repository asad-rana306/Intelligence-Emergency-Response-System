package com.iers.realtime.dto;

import java.util.Map;

/**
 * A call set-up message (contract: "Call messages").
 * A phone sends it to /app/call/{incidentId}; the other person receives it on /user/queue/call.
 * "from" is filled in by the server. Whatever a phone puts there is ignored.
 */
public record CallMessage(String incidentId, String from, String kind, Map<String, Object> payload) { }
