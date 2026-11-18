package com.nocook.service.application;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs a hook after the surrounding transaction commits (immediately when there is none). */
public final class AfterCommit {

    private AfterCommit() {}

    public static void run(Runnable hook) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            hook.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                hook.run();
            }
        });
    }
}
