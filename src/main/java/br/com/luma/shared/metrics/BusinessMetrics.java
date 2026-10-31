package br.com.luma.shared.metrics;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class BusinessMetrics {

    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void pieceReceived() {
        afterCommit(() -> pieceMovements("received").increment());
    }

    public void pieceLabeled() {
        afterCommit(() -> pieceMovements("labeled").increment());
    }

    public void consignmentCreated(int pieces) {
        afterCommit(() -> {
            Counter.builder("luma.consignments.created")
                    .description("Lotes de consignação criados")
                    .register(registry)
                    .increment();
            consignmentItems("delivered").increment(pieces);
        });
    }

    public void consignmentItemSold() {
        afterCommit(() -> consignmentItems("sold").increment());
    }

    public void consignmentItemReturned() {
        afterCommit(() -> consignmentItems("returned").increment());
    }

    public void photoUploaded(long sizeBytes) {
        afterCommit(() -> {
            Counter.builder("luma.photos.uploaded")
                    .description("Fotos de peças enviadas")
                    .register(registry)
                    .increment();
            DistributionSummary.builder("luma.photos.size")
                    .description("Tamanho das fotos enviadas")
                    .baseUnit("bytes")
                    .register(registry)
                    .record(sizeBytes);
        });
    }

    public void login(boolean succeeded) {
        afterCommit(() -> Counter.builder("luma.auth.logins")
                .description("Tentativas de login")
                .tag("result", succeeded ? "success" : "failure")
                .register(registry)
                .increment());
    }

    private Counter pieceMovements(String action) {
        return Counter.builder("luma.pieces.movements")
                .description("Recebimentos e etiquetagens de peças")
                .tag("action", action)
                .register(registry);
    }

    private Counter consignmentItems(String action) {
        return Counter.builder("luma.consignment.items")
                .description("Peças entregues, vendidas e devolvidas em consignação")
                .tag("action", action)
                .register(registry);
    }

    private static void afterCommit(Runnable action) {
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
