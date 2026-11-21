package br.com.luma.shared.logging;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Roda uma ação só depois que a transação confirma, para não logar uma operação que foi desfeita. Fora de
 * transação, roda na hora. Roda na mesma thread da requisição, então o MDC (requestId, userId) continua lá.
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
