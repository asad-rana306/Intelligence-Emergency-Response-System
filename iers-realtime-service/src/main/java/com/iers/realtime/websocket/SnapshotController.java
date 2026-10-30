package com.iers.realtime.websocket;

import com.iers.realtime.service.SnapshotService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/** A phone sends (empty) to /app/snapshot to get a fresh SNAPSHOT on /user/queue/events. */
@Controller
public class SnapshotController {

    private final SnapshotService snapshots;

    public SnapshotController(SnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    @MessageMapping("/snapshot")
    public void snapshot(Principal principal) {
        snapshots.send(principal.getName());
    }
}
