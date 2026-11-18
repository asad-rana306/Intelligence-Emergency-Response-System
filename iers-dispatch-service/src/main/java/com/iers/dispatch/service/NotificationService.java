package com.iers.dispatch.service;

import com.iers.dispatch.config.PushProperties;
import com.iers.dispatch.push.PushMessage;
import com.iers.dispatch.push.PushService;
import com.iers.dispatch.service.ContactAlertService.FollowUp;
import com.iers.dispatch.util.AfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The one place the rest of Dispatch calls to tell people something.
 *
 * Every method:
 *  - returns at once (the real work runs on BackgroundRunner),
 *  - waits for the database commit if a transaction is open (nobody hears about something that
 *    was rolled back, and the background worker finds the saved row),
 *  - never throws.
 *
 * Who is told what:
 *   emergency contacts : SMS   (crash alert, on the way, nobody accepted yet, false alarm)
 *   responder phone    : push  (OFFER, STAND_DOWN, OFF_DUTY)
 *   driver phone       : push  (ACCEPTED)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final BackgroundRunner runner;
    private final ContactAlertService contactAlerts;
    private final PushService pushService;
    private final PushProperties pushProps;

    /**
     * A new incident exists: alert the emergency contacts. If the queue is full or anything fails,
     * the sweep picks the alert up again (DispatchSweepService.sweepContactAlerts).
     */
    public void crashDetected(UUID incidentId) {
        later("contact alert " + incidentId, () -> contactAlerts.sendCrashAlert(incidentId));
    }

    /** A responder accepted: tell the driver (push) and the contacts (SMS). */
    public void responderOnTheWay(UUID incidentId, UUID driverId, String responderName) {
        later("on-the-way SMS " + incidentId, () -> contactAlerts.sendFollowUp(incidentId, FollowUp.ON_THE_WAY));
        if (driverId != null) {
            later("accepted push " + incidentId,
                    () -> pushService.sendToUser(driverId, PushMessage.accepted(incidentId, responderName)));
        }
    }

    /** Nobody accepted for 2 minutes: tell the contacts to call the emergency number themselves. */
    public void noResponderYet(UUID incidentId) {
        later("no-responder SMS " + incidentId, () -> contactAlerts.sendFollowUp(incidentId, FollowUp.NO_RESPONDER));
    }

    /** The driver cancelled: tell the contacts who got the first SMS. */
    public void falseAlarm(UUID incidentId) {
        later("false-alarm SMS " + incidentId, () -> contactAlerts.sendFollowUp(incidentId, FollowUp.FALSE_ALARM));
    }

    /** Ring the responder's phone (high priority push, works when the app is closed). */
    public void offerToResponder(UUID responderId, UUID incidentId, String driverName, Double lat, Double lng) {
        later("offer push " + incidentId, () -> pushService.sendToUser(responderId,
                PushMessage.offer(incidentId, lat, lng, driverName, pushProps.offerTtl())));
    }

    /** The offer is cancelled (late cancel). */
    public void standDown(UUID responderId, UUID incidentId) {
        later("stand-down push " + incidentId,
                () -> pushService.sendToUser(responderId, PushMessage.standDown(incidentId)));
    }

    /** "You are now OFF duty" (shift over or phone silent). Reason: MAX_DURATION or HEARTBEAT_LOST. */
    public void dutyExpired(UUID userId, String reason) {
        later("off-duty push " + userId, () -> pushService.sendToUser(userId, PushMessage.offDuty(reason)));
    }

    /**
     * Forward medical profile and ETA to the hospital dashboard.
     * Not built yet: there is no hospital system to call. It only writes the log.
     */
    public void sendHospitalPreAlert(String hospitalId, UUID incidentId,
                                     String bloodType, String allergies, String eta) {
        log.info("HOSPITAL PRE-ALERT (log only) hospital={} incident={} bloodType={} allergies={} ETA={}",
                hospitalId, incidentId, bloodType, allergies, eta);
    }

    private void later(String what, Runnable task) {
        AfterCommit.run(() -> runner.run(what, task));
    }
}
