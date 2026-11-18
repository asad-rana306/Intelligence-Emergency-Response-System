package com.iers.dispatch.service;

import com.iers.dispatch.config.PushProperties;
import com.iers.dispatch.push.PushMessage;
import com.iers.dispatch.push.PushService;
import com.iers.dispatch.service.ContactAlertService.FollowUp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private BackgroundRunner runner;
    @Mock private ContactAlertService contactAlerts;
    @Mock private PushService pushService;

    private NotificationService service;
    private final UUID incidentId = UUID.randomUUID();
    private final UUID responderId = UUID.randomUUID();
    private final UUID driverId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // The fake runner runs the task right away, on the test thread.
        lenient().when(runner.run(anyString(), any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        });
        service = new NotificationService(runner, contactAlerts, pushService,
                new PushProperties(true, null, null, Duration.ofSeconds(30)));
    }

    private PushMessage pushedTo(UUID user) {
        ArgumentCaptor<PushMessage> captor = ArgumentCaptor.forClass(PushMessage.class);
        verify(pushService).sendToUser(eq(user), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("crashDetected — the contact alert runs in the background")
    void crashDetected() {
        service.crashDetected(incidentId);
        verify(contactAlerts).sendCrashAlert(incidentId);
    }

    @Test
    @DisplayName("responderOnTheWay — SMS to contacts and a push to the driver")
    void onTheWay() {
        service.responderOnTheWay(incidentId, driverId, "Bilal");

        verify(contactAlerts).sendFollowUp(incidentId, FollowUp.ON_THE_WAY);
        PushMessage push = pushedTo(driverId);
        assertThat(push.data()).containsEntry("type", "ACCEPTED").containsEntry("responderName", "Bilal");
    }

    @Test
    @DisplayName("responderOnTheWay — no driver id: only the SMS")
    void onTheWayWithoutDriver() {
        service.responderOnTheWay(incidentId, null, "Bilal");

        verify(contactAlerts).sendFollowUp(incidentId, FollowUp.ON_THE_WAY);
        verifyNoInteractions(pushService);
    }

    @Test
    @DisplayName("noResponderYet and falseAlarm go to the contacts")
    void followUps() {
        service.noResponderYet(incidentId);
        service.falseAlarm(incidentId);

        verify(contactAlerts).sendFollowUp(incidentId, FollowUp.NO_RESPONDER);
        verify(contactAlerts).sendFollowUp(incidentId, FollowUp.FALSE_ALARM);
    }

    @Test
    @DisplayName("offerToResponder — urgent push with the incident, the place and the offer time as lifetime")
    void offer() {
        service.offerToResponder(responderId, incidentId, "Ali", 31.5, 74.3);

        PushMessage push = pushedTo(responderId);
        assertThat(push.data()).containsEntry("type", "OFFER")
                .containsEntry("incidentId", incidentId.toString())
                .containsEntry("lat", "31.5").containsEntry("lng", "74.3")
                .containsEntry("driverName", "Ali");
        assertThat(push.urgent()).isTrue();
        assertThat(push.ttl()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("standDown and dutyExpired push to the right user")
    void standDownAndDutyExpired() {
        service.standDown(responderId, incidentId);
        PushMessage stand = pushedTo(responderId);
        assertThat(stand.data()).containsEntry("type", "STAND_DOWN").containsEntry("incidentId", incidentId.toString());

        UUID other = UUID.randomUUID();
        service.dutyExpired(other, "MAX_DURATION");
        PushMessage off = pushedTo(other);
        assertThat(off.data()).containsEntry("type", "OFF_DUTY").containsEntry("reason", "MAX_DURATION");
    }

    @Test
    @DisplayName("inside a transaction nothing starts before the commit")
    void waitsForCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.crashDetected(incidentId);
            verifyNoInteractions(runner, contactAlerts);

            new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
                    .forEach(TransactionSynchronization::afterCommit);

            verify(contactAlerts).sendCrashAlert(incidentId);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("inside a transaction that rolls back nobody is told")
    void rollbackTellsNobody() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.offerToResponder(responderId, incidentId, "Ali", 1.0, 2.0);
            new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verifyNoInteractions(pushService);
    }

    @Test
    @DisplayName("a full queue does not throw: the rescue goes on (the sweep retries contact alerts)")
    void fullQueueDoesNotThrow() {
        lenient().when(runner.run(anyString(), any(Runnable.class))).thenReturn(false);

        assertThatCode(() -> {
            service.crashDetected(incidentId);
            service.offerToResponder(responderId, incidentId, "Ali", 1.0, 2.0);
        }).doesNotThrowAnyException();
        verify(contactAlerts, never()).sendCrashAlert(any());
    }

    @Test
    @DisplayName("hospital pre-alert only logs")
    void hospitalPreAlert() {
        assertThatCode(() -> service.sendHospitalPreAlert("H1", incidentId, "O+", "none", "5 min"))
                .doesNotThrowAnyException();
    }
}
