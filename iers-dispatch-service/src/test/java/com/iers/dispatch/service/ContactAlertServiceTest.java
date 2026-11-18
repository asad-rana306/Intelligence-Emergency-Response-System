package com.iers.dispatch.service;

import com.iers.dispatch.config.NotifyProperties;
import com.iers.dispatch.dto.feign.EmergencyContactDto;
import com.iers.dispatch.entity.Incident;
import com.iers.dispatch.entity.enums.IncidentStatus;
import com.iers.dispatch.exception.AuthUnavailableException;
import com.iers.dispatch.feign.AuthServiceClient;
import com.iers.dispatch.repository.IncidentRepository;
import com.iers.dispatch.service.ContactAlertService.FollowUp;
import com.iers.dispatch.sms.SmsResult;
import com.iers.dispatch.sms.SmsSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContactAlertServiceTest {

    private static final String MOM = "+923001110001";
    private static final String DAD = "+923001110002";

    @Mock private IncidentRepository incidentRepository;
    @Mock private AuthServiceClient authClient;
    @Mock private SmsSender smsSender;

    private final NotifyProperties props = new NotifyProperties("http://track/", "1122",
            Duration.ofSeconds(20), Duration.ofMinutes(2), Duration.ofMinutes(30), 20,
            new NotifyProperties.Executor(1, 10));

    private ContactAlertService service;
    private final UUID incidentId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();
    private Incident incident;

    @BeforeEach
    void setUp() {
        service = new ContactAlertService(incidentRepository, authClient, smsSender, props);
        incident = Incident.builder().id(incidentId).crashEventId(UUID.randomUUID()).driverId(driverId)
                .driverName("Ali Khan").driverPhone("+923009998888")
                .gpsLat(31.52).gpsLng(74.35).status(IncidentStatus.DISPATCHING).build();
    }

    private EmergencyContactDto contact(String phone) {
        return EmergencyContactDto.builder().contactName("c").phone(phone).build();
    }

    private void claimWins() {
        when(incidentRepository.claimContactAlert(eq(incidentId), eq(IncidentService.ACTIVE),
                any(Instant.class), any(Instant.class))).thenReturn(1);
        when(incidentRepository.findById(incidentId)).thenAnswer(inv -> Optional.of(incident));
    }

    // ══════════════════ crash alert ══════════════════

    @Test
    @DisplayName("somebody else owns the alert (or it is done / the incident is over): this call does nothing")
    void claimLost() {
        when(incidentRepository.claimContactAlert(eq(incidentId), eq(IncidentService.ACTIVE),
                any(Instant.class), any(Instant.class))).thenReturn(0);

        assertThat(service.sendCrashAlert(incidentId)).isFalse();

        verifyNoInteractions(authClient, smsSender);
        verify(incidentRepository, never()).findById(any());
    }

    @Test
    @DisplayName("every contact gets the SMS, each one is saved as it is sent, then the alert is marked done")
    void allContactsAlerted() {
        claimWins();
        when(authClient.getEmergencyContacts(driverId)).thenReturn(List.of(contact(MOM), contact(DAD)));
        when(smsSender.send(anyString(), anyString())).thenReturn(SmsResult.sent());

        assertThat(service.sendCrashAlert(incidentId)).isTrue();

        verify(smsSender).send(eq(MOM), contains("EMERGENCY: Ali Khan"));
        verify(smsSender).send(eq(DAD), contains("EMERGENCY: Ali Khan"));
        InOrder order = inOrder(incidentRepository);
        order.verify(incidentRepository).saveContactsSent(eq(incidentId), eq(MOM), any(Instant.class));
        order.verify(incidentRepository).saveContactsSent(eq(incidentId), eq(MOM + "," + DAD), any(Instant.class));
        order.verify(incidentRepository).markContactsNotified(eq(incidentId), eq(MOM + "," + DAD), any(Instant.class));
        verify(incidentRepository, never()).scheduleContactRetry(any(), any());
    }

    @Test
    @DisplayName("the SMS does not wait for an ambulance: it is sent even though nobody is assigned")
    void sentWithoutAmbulance() {
        incident.setAssignedResponderId(null);   // nobody holds the incident
        claimWins();
        when(authClient.getEmergencyContacts(driverId)).thenReturn(List.of(contact(MOM)));
        when(smsSender.send(anyString(), anyString())).thenReturn(SmsResult.queued());

        assertThat(service.sendCrashAlert(incidentId)).isTrue();

        verify(smsSender).send(eq(MOM), anyString());
    }

    @Test
    @DisplayName("no contacts at all: nothing to send, the alert is done")
    void noContacts() {
        claimWins();
        when(authClient.getEmergencyContacts(driverId)).thenReturn(List.of());

        assertThat(service.sendCrashAlert(incidentId)).isTrue();

        verifyNoInteractions(smsSender);
        verify(incidentRepository).markContactsNotified(eq(incidentId), eq(""), any(Instant.class));
    }

    @Test
    @DisplayName("an incident without a driver id has nobody to tell; Auth is not called")
    void noDriver() {
        incident.setDriverId(null);
        claimWins();

        assertThat(service.sendCrashAlert(incidentId)).isTrue();

        verifyNoInteractions(authClient, smsSender);
    }

    @Test
    @DisplayName("one SMS fails: the contact that got it is saved, the alert is NOT done, a retry is scheduled")
    void partialFailure() {
        claimWins();
        when(authClient.getEmergencyContacts(driverId)).thenReturn(List.of(contact(MOM), contact(DAD)));
        when(smsSender.send(eq(MOM), anyString())).thenReturn(SmsResult.sent());
        when(smsSender.send(eq(DAD), anyString())).thenReturn(SmsResult.failed("HTTP 500"));

        assertThat(service.sendCrashAlert(incidentId)).isFalse();

        verify(incidentRepository).saveContactsSent(eq(incidentId), eq(MOM), any(Instant.class));
        verify(incidentRepository, never()).markContactsNotified(any(), any(), any());
        verify(incidentRepository).scheduleContactRetry(eq(incidentId), any(Instant.class));
    }

    @Test
    @DisplayName("the retry sends only to the contacts that did not get the SMS yet")
    void retrySkipsContactsAlreadyTold() {
        incident.setContactsSent(MOM);
        claimWins();
        when(authClient.getEmergencyContacts(driverId)).thenReturn(List.of(contact(MOM), contact(DAD)));
        when(smsSender.send(eq(DAD), anyString())).thenReturn(SmsResult.sent());

        assertThat(service.sendCrashAlert(incidentId)).isTrue();

        verify(smsSender, never()).send(eq(MOM), anyString());
        verify(smsSender).send(eq(DAD), anyString());
        verify(incidentRepository).markContactsNotified(eq(incidentId), eq(MOM + "," + DAD), any(Instant.class));
    }

    @Test
    @DisplayName("Auth cannot give the contacts: nothing is skipped, a retry is scheduled")
    void authDown() {
        claimWins();
        when(authClient.getEmergencyContacts(driverId))
                .thenThrow(new AuthUnavailableException("down", new RuntimeException()));

        assertThat(service.sendCrashAlert(incidentId)).isFalse();

        verifyNoInteractions(smsSender);
        verify(incidentRepository).scheduleContactRetry(eq(incidentId), any(Instant.class));
        verify(incidentRepository, never()).markContactsNotified(any(), any(), any());
    }

    @Test
    @DisplayName("a sender that throws counts as a failed SMS, not as a crash")
    void senderThrows() {
        claimWins();
        when(authClient.getEmergencyContacts(driverId)).thenReturn(List.of(contact(MOM)));
        when(smsSender.send(anyString(), anyString())).thenThrow(new IllegalStateException("boom"));

        assertThat(service.sendCrashAlert(incidentId)).isFalse();

        verify(incidentRepository).scheduleContactRetry(eq(incidentId), any(Instant.class));
    }

    @Test
    @DisplayName("the incident ended while alerting (false alarm): no more SMS are sent")
    void incidentEnded() {
        incident.setStatus(IncidentStatus.CANCELLED_BY_USER);
        claimWins();
        when(authClient.getEmergencyContacts(driverId)).thenReturn(List.of(contact(MOM)));

        assertThat(service.sendCrashAlert(incidentId)).isFalse();

        verifyNoInteractions(smsSender);
        verify(incidentRepository, never()).markContactsNotified(any(), any(), any());
    }

    @Test
    @DisplayName("blank and repeated numbers are ignored: one SMS per phone")
    void blankAndDuplicatePhones() {
        claimWins();
        when(authClient.getEmergencyContacts(driverId))
                .thenReturn(List.of(contact(MOM), contact(MOM), contact(" "), contact(null)));
        when(smsSender.send(anyString(), anyString())).thenReturn(SmsResult.sent());

        assertThat(service.sendCrashAlert(incidentId)).isTrue();

        verify(smsSender, times(1)).send(eq(MOM), anyString());
    }

    // ══════════════════ follow-ups ══════════════════

    @Test
    @DisplayName("follow-up goes only to the contacts who got the first SMS")
    void followUpOnlyToAlertedContacts() {
        incident.setContactsSent(MOM + "," + DAD);
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(smsSender.send(anyString(), anyString())).thenReturn(SmsResult.sent());

        service.sendFollowUp(incidentId, FollowUp.ON_THE_WAY);

        verify(smsSender).send(eq(MOM), contains("on the way"));
        verify(smsSender).send(eq(DAD), contains("on the way"));
    }

    @Test
    @DisplayName("follow-up with nobody alerted yet sends nothing")
    void followUpWithoutRecipients() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));

        service.sendFollowUp(incidentId, FollowUp.NO_RESPONDER);

        verifyNoInteractions(smsSender);
    }

    @Test
    @DisplayName("follow-up for an unknown incident is ignored")
    void followUpUnknownIncident() {
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.empty());
        service.sendFollowUp(incidentId, FollowUp.FALSE_ALARM);
        verifyNoInteractions(smsSender);
    }

    @Test
    @DisplayName("a failing follow-up SMS does not stop the others and never throws")
    void followUpFailureContinues() {
        incident.setContactsSent(MOM + "," + DAD);
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(smsSender.send(eq(MOM), anyString())).thenThrow(new IllegalStateException("boom"));
        when(smsSender.send(eq(DAD), anyString())).thenReturn(SmsResult.sent());

        service.sendFollowUp(incidentId, FollowUp.FALSE_ALARM);

        verify(smsSender).send(eq(DAD), contains("false alarm"));
    }

    // ══════════════════ texts ══════════════════

    @Test
    @DisplayName("crash text: name, map link, tracking link (one slash), driver phone, each link on its own line")
    void crashText() {
        String text = ContactAlertService.crashText(incident, props);

        assertThat(text).startsWith("EMERGENCY: Ali Khan may have been in a car accident.")
                .contains("\nLocation: https://maps.google.com/?q=31.520000,74.350000\n")
                .contains("\nLive tracking: http://track/" + incidentId + "\n")
                .contains("An ambulance is being sent.")
                .endsWith("Phone: +923009998888");
        assertThat(text).doesNotContain("//" + incidentId);
    }

    @Test
    @DisplayName("crash text: no GPS or phone, no name — still a readable message")
    void crashTextMissingParts() {
        incident.setGpsLat(null);
        incident.setDriverPhone(null);
        incident.setDriverName("  ");

        String text = ContactAlertService.crashText(incident, props);

        assertThat(text).startsWith("EMERGENCY: Your contact may have been")
                .doesNotContain("Location:")
                .doesNotContain("Phone:");
    }

    @Test
    @DisplayName("a very long or multi-line name is shortened to one line")
    void longName() {
        incident.setDriverName("A\nVery   " + "Long".repeat(30));

        String text = ContactAlertService.crashText(incident, props);

        String firstLine = text.split("\n")[0];
        assertThat(firstLine).startsWith("EMERGENCY: A Very Long");
        assertThat(firstLine.length()).isLessThan(120);
    }

    @Test
    @DisplayName("no-responder text tells the contacts to call the national number and gives the location")
    void noResponderText() {
        String text = ContactAlertService.followUpText(FollowUp.NO_RESPONDER, incident, props);

        assertThat(text).startsWith("URGENT:").contains("Please call 1122 now.")
                .contains("https://maps.google.com/?q=31.520000,74.350000");
    }

    @Test
    @DisplayName("false-alarm and on-the-way texts")
    void otherFollowUpTexts() {
        assertThat(ContactAlertService.followUpText(FollowUp.FALSE_ALARM, incident, props))
                .contains("false alarm").contains("Ali Khan is safe");
        assertThat(ContactAlertService.followUpText(FollowUp.ON_THE_WAY, incident, props))
                .contains("on the way").contains("http://track/" + incidentId);
    }

    @Test
    @DisplayName("the list of alerted phones is stored as comma text and read back in the same order")
    void parseAndJoin() {
        assertThat(ContactAlertService.parse(null)).isEmpty();
        assertThat(ContactAlertService.parse(" ")).isEmpty();
        assertThat(ContactAlertService.parse(MOM + ", " + DAD + ",")).containsExactly(MOM, DAD);
        assertThat(ContactAlertService.join(ContactAlertService.parse(MOM + "," + DAD))).isEqualTo(MOM + "," + DAD);
    }
}
