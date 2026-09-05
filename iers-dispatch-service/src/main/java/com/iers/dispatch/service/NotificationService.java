package com.iers.dispatch.service;

import com.iers.dispatch.dto.feign.EmergencyContactDto;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class NotificationService {

    @Value("${dispatch.twilio.account-sid:}")
    private String accountSid;

    @Value("${dispatch.twilio.auth-token:}")
    private String authToken;

    @Value("${dispatch.twilio.from-number:}")
    private String fromNumber;

    @Value("${dispatch.twilio.enabled:false}")
    private boolean twilioEnabled;

    @Value("${dispatch.tracking-base-url:http://localhost:3000/track}")
    private String trackingBaseUrl;

    @PostConstruct
    public void init() {
        if (twilioEnabled && !accountSid.isBlank() && !authToken.isBlank()) {
            Twilio.init(accountSid, authToken);
            log.info("Twilio initialized with account SID: {}...", accountSid.substring(0, 8));
        } else {
            log.info("Twilio SMS DISABLED — notifications will be logged only");
        }
    }

    /**
     * Send SMS alerts to all emergency contacts with a tracking link.
     */
    public void sendEmergencySms(List<EmergencyContactDto> contacts,
                                 String driverName, UUID incidentId) {
        String trackingUrl = trackingBaseUrl + "/" + incidentId;
        String body = String.format(
                "EMERGENCY: %s has been in a car accident. " +
                "Live tracking: %s — Emergency services have been dispatched.",
                driverName, trackingUrl);

        for (EmergencyContactDto contact : contacts) {
            sendSms(contact.getPhone(), body);
        }
    }

    /**
     * Send 'False Alarm' update to all emergency contacts.
     */
    public void sendFalseAlarmSms(List<EmergencyContactDto> contacts, String driverName) {
        String body = String.format(
                "UPDATE: The emergency alert for %s was a false alarm. " +
                "%s is safe. Please disregard the previous message.",
                driverName, driverName);

        for (EmergencyContactDto contact : contacts) {
            sendSms(contact.getPhone(), body);
        }
    }

    /**
     * Simulate a high-priority push notification to a responder's device.
     * In production, this would use Firebase Cloud Messaging (FCM).
     */
    public void sendResponderPush(UUID responderId, String vehicleId,
                                  UUID incidentId, String driverName,
                                  Double lat, Double lng, int priority) {
        log.info("🚨 PUSH NOTIFICATION → Responder {} (vehicle={}): " +
                 "EMERGENCY DISPATCH — {} at [{},{}], priority={}, incident={}",
                responderId, vehicleId, driverName, lat, lng, priority, incidentId);
    }

    /**
     * Send a stand-down push to a responder (late cancel or reassignment).
     */
    public void sendStandDownPush(UUID responderId, UUID incidentId) {
        log.info("🔕 STAND DOWN → Responder {}: Incident {} has been cancelled", responderId, incidentId);
    }

    /**
     * Forward medical profile and ETA to the hospital dashboard.
     */
    public void sendHospitalPreAlert(String hospitalId, UUID incidentId,
                                     String bloodType, String allergies, String eta) {
        log.info("🏥 HOSPITAL PRE-ALERT → Hospital {}: Incoming patient for incident={}, " +
                 "bloodType={}, allergies={}, ETA={}",
                hospitalId, incidentId, bloodType, allergies, eta);
    }

    // ── Internal ──

    private void sendSms(String to, String body) {
        if (twilioEnabled && !fromNumber.isBlank()) {
            try {
                Message message = Message.creator(
                        new PhoneNumber(to),
                        new PhoneNumber(fromNumber),
                        body
                ).create();
                log.info("SMS sent to {}: sid={}", to, message.getSid());
            } catch (Exception e) {
                log.error("Twilio SMS failed to {}: {}", to, e.getMessage());
            }
        } else {
            log.info("📱 SMS (simulated) → {}: {}", to, body);
        }
    }
}
