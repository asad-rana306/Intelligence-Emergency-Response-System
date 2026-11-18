package com.iers.dispatch.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AfterCommitTest {

    @Test
    @DisplayName("no transaction: the action runs at once")
    void runsAtOnceOutsideTransaction() {
        AtomicInteger runs = new AtomicInteger();
        AfterCommit.run(runs::incrementAndGet);
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("inside a transaction: the action waits for the commit")
    void waitsForCommit() {
        AtomicInteger runs = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        try {
            AfterCommit.run(runs::incrementAndGet);
            assertThat(runs.get()).isZero();

            List<TransactionSynchronization> syncs =
                    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
            syncs.forEach(TransactionSynchronization::afterCommit);

            assertThat(runs.get()).isEqualTo(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("inside a transaction that rolls back: the action never runs")
    void neverRunsOnRollback() {
        AtomicInteger runs = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        try {
            AfterCommit.run(runs::incrementAndGet);
            // rollback: Spring calls afterCompletion(STATUS_ROLLED_BACK), never afterCommit()
            new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(runs.get()).isZero();
    }
}
