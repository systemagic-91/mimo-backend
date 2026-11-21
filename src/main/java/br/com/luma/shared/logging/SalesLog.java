package br.com.luma.shared.logging;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vendas: sempre registradas (decisão de 2026-11-21). O logger {@code br.com.luma.sales} tem nível próprio
 * {@code INFO} no application.yml, sem variável de ambiente, e o Actuator recusa mudá-lo. Por ter nível
 * próprio, não herda o de {@code br.com.luma}: continua ligado mesmo com o resto em ERROR ou OFF.
 */
public final class SalesLog {

    public static final String LOGGER_NAME = "br.com.luma.sales";
    private static final Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);

    private SalesLog() {
    }

    /** Chamado dentro da transação da venda; a linha só sai depois do commit. */
    public static void saleRegistered(UUID consignmentId, String consignmentCode, UUID pieceId, String pieceCode,
            UUID sellerId, long valueCents, int commissionRate, Instant soldAt, boolean retroactive) {
        AfterCommit.run(() -> LOGGER.info(
                "Sale registered consignmentId={} consignmentCode={} pieceId={} pieceCode={} sellerId={}"
                        + " valueCents={} commissionRate={} soldAt={} retroactive={}",
                consignmentId, consignmentCode, pieceId, pieceCode, sellerId, valueCents, commissionRate, soldAt,
                retroactive));
    }
}
