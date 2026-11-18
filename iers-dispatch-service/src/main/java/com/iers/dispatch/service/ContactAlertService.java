package com.iers.dispatch.service;

import com.iers.dispatch.config.NotifyProperties;
import com.iers.dispatch.dto.feign.EmergencyContactDto;
import com.iers.dispatch.entity.Incident;
import com.iers.dispatch.feign.AuthServiceClient;
import com.iers.dispatch.repository.IncidentRepository;
import com.iers.dispatch.sms.SmsResult;
import com.iers.dispatch.sms.SmsSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tells the driver's emergency contacts about the accident.
 *
 * THE RULE: this does not depend on an ambulance. The contacts are told as soon as the incident
 * exists, and they are told again what to do ("call 1122") if nobody accepts the call.
 *
 * Reliability (the crash alert):
 *  - It runs in the background, never inside the dispatch transaction.
 *  - One worker "owns" the alert through a lease column (see IncidentRepository.claimContactAlert),
 *    so many Dispatch instances can run the sweep without sending the SMS twice.
 *  - Each contact that accepted the SMS is saved at once (contacts_sent). If Auth, the SMS gateway
 *    or this service fails half way, the retry sends only to the contacts that are still missing.
 *  - If anything fails the lease is shortened and the sweep tries again, until give-up-after.
 *
 * Follow-up SMS (ambulance on the way, nobody accepted yet, false alarm) go only to the contacts
 * that really got the first SMS. They are best effort: no retry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContactAlertService {

    public enum FollowUp { ON_THE_WAY, NO_RESPONDER, FALSE_ALARM }

    private static final String SEPARATOR = ",";
    private static final int MAX_NAME_LENGTH = 40;

    private final IncidentRepository incidentRepository;
    private final AuthServiceClient authClient;
    private final SmsSender smsSender;
    private final NotifyProperties props;

    /**
     * Send the crash SMS to every contact that has not got it yet.
     *
     * @return true if everybody has been told (or there is nobody to tell); false if this call
     *         did nothing (someone else owns it, the incident is over) or must be retried
     */
    public boolean sendCrashAlert(UUID incidentId) {
        Instant now = Instant.now();
        int claimed = incidentRepository.claimContactAlert(incidentId, IncidentService.ACTIVE,
                now, now.plus(props.contactLease()));
        if (claimed == 0) {
            log.debug("Contact alert for incident {}: not mine, already done, or incident is over", incidentId);
            return false;
        }

        Incident incident = incidentRepository.findById(incidentId).orElse(null);
        if (incident == null) {
            return false;
        }

        List<EmergencyContactDto> contacts;
        if (incident.getDriverId() == null) {
            contacts = List.of();
        } else {
            try {
                contacts = authClient.getEmergencyContacts(incident.getDriverId());
            } catch (RuntimeException e) {
                log.warn("Contact alert for incident {}: cannot read the contacts ({}), will retry",
                        incidentId, e.getMessage());
                retryLater(incidentId);
                return false;
            }
        }

        Set<String> sent = parse(incident.getContactsSent());
        String text = crashText(incident, props);
        boolean allDone = true;

        if (contacts != null) {
            for (EmergencyContactDto contact : contacts) {
                String phone = contact.getPhone();
                if (phone == null || phone.isBlank() || sent.contains(phone)) {
                    continue;
                }
                if (!stillActive(incidentId)) {
                    log.info("Incident {} ended while alerting contacts - stopping", incidentId);
                    return false;
                }
                if (send(phone, text)) {
                    sent.add(phone);
                    incidentRepository.saveContactsSent(incidentId, join(sent),
                            Instant.now().plus(props.contactLease()));
                } else {
                    allDone = false;
                }
            }
        }

        if (allDone) {
            incidentRepository.markContactsNotified(incidentId, join(sent), Instant.now());
            log.info("Contacts of incident {} alerted ({} SMS)", incidentId, sent.size());
            return true;
        }
        log.warn("Contact alert for incident {}: {} contact(s) told, others failed - will retry",
                incidentId, sent.size());
        retryLater(incidentId);
        return false;
    }

    /** Best-effort message to the contacts that got the crash SMS. */
    public void sendFollowUp(UUID incidentId, FollowUp kind) {
        Incident incident = incidentRepository.findById(incidentId).orElse(null);
        if (incident == null) {
            return;
        }
        Set<String> recipients = parse(incident.getContactsSent());
        if (recipients.isEmpty()) {
            log.debug("Follow-up {} for incident {}: nobody was alerted, nothing to send", kind, incidentId);
            return;
        }
        String text = followUpText(kind, incident, props);
        for (String phone : recipients) {
            send(phone, text);
        }
        log.info("Follow-up {} sent to {} contact(s) of incident {}", kind, recipients.size(), incidentId);
    }

    // ── helpers ──

    private boolean send(String phone, String text) {
        try {
            SmsResult result = smsSender.send(phone, text);
            return result != null && result.accepted();
        } catch (RuntimeException e) {
            log.error("SMS sender threw for ***{}: {}", tail(phone), e.getMessage());
            return false;
        }
    }

    private boolean stillActive(UUID incidentId) {
        return incidentRepository.findById(incidentId)
                .map(i -> IncidentService.ACTIVE.contains(i.getStatus()))
                .orElse(false);
    }

    private void retryLater(UUID incidentId) {
        incidentRepository.scheduleContactRetry(incidentId, Instant.now().plus(props.contactRetryInterval()));
    }

    static Set<String> parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(stored.split(SEPARATOR))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static String join(Set<String> phones) {
        return String.join(SEPARATOR, phones);
    }

    // ── texts (plain ASCII so one SMS is not cut short by special characters) ──

    static String crashText(Incident incident, NotifyProperties props) {
        // Each link is on its own line so a messaging app does not swallow a full stop into the link.
        StringBuilder sb = new StringBuilder("EMERGENCY: ")
                .append(name(incident)).append(" may have been in a car accident.");
        appendLocation(sb, incident);
        sb.append("\nLive tracking: ").append(trackingUrl(incident, props));
        sb.append("\nAn ambulance is being sent.");
        if (incident.getDriverPhone() != null && !incident.getDriverPhone().isBlank()) {
            sb.append("\nPhone: ").append(incident.getDriverPhone().trim());
        }
        return sb.toString();
    }

    static String followUpText(FollowUp kind, Incident incident, NotifyProperties props) {
        String name = name(incident);
        return switch (kind) {
            case ON_THE_WAY -> "UPDATE: an ambulance accepted the call for " + name
                    + " and is on the way.\nLive tracking: " + trackingUrl(incident, props);
            case NO_RESPONDER -> {
                StringBuilder sb = new StringBuilder("URGENT: no ambulance has accepted the call for ")
                        .append(name).append(" yet. Please call ").append(props.emergencyNumber()).append(" now.");
                appendLocation(sb, incident);
                yield sb.toString();
            }
            case FALSE_ALARM -> "UPDATE: the alert for " + name + " was a false alarm. "
                    + name + " is safe. Please ignore the earlier message.";
        };
    }

    private static void appendLocation(StringBuilder sb, Incident incident) {
        if (incident.getGpsLat() != null && incident.getGpsLng() != null) {
            sb.append(String.format(Locale.ROOT, "\nLocation: https://maps.google.com/?q=%.6f,%.6f",
                    incident.getGpsLat(), incident.getGpsLng()));
        }
    }

    private static String trackingUrl(Incident incident, NotifyProperties props) {
        String base = props.trackingBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + incident.getId();
    }

    private static String name(Incident incident) {
        String n = incident.getDriverName();
        if (n == null || n.isBlank()) {
            return "Your contact";
        }
        n = n.trim().replaceAll("\\s+", " ");
        return n.length() > MAX_NAME_LENGTH ? n.substring(0, MAX_NAME_LENGTH) : n;
    }

    private static String tail(String phone) {
        return phone.length() < 4 ? "***" : phone.substring(phone.length() - 4);
    }
}
