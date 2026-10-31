package br.com.luma.shared.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class BusinessMetricsTests {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BusinessMetrics metrics = new BusinessMetrics(registry);

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void countsImmediatelyOutsideATransaction() {
        metrics.login(true);
        metrics.login(false);
        metrics.login(false);

        assertThat(registry.get("luma.auth.logins").tag("result", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get("luma.auth.logins").tag("result", "failure").counter().count()).isEqualTo(2);
    }

    @Test
    void insideATransactionCountsOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        metrics.consignmentCreated(3);

        assertThat(registry.find("luma.consignments.created").counter()).isNull();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(registry.get("luma.consignments.created").counter().count()).isEqualTo(1);
        assertThat(registry.get("luma.consignment.items").tag("action", "delivered").counter().count())
                .isEqualTo(3);
    }

    @Test
    void rolledBackTransactionDoesNotCount() {
        TransactionSynchronizationManager.initSynchronization();

        metrics.consignmentItemSold();

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(registry.find("luma.consignment.items").counter()).isNull();
    }

    @Test
    void photoUploadsRecordCountAndSize() {
        metrics.photoUploaded(30_000);
        metrics.photoUploaded(10_000);

        assertThat(registry.get("luma.photos.uploaded").counter().count()).isEqualTo(2);
        assertThat(registry.get("luma.photos.size").summary().totalAmount()).isEqualTo(40_000);
    }
}
