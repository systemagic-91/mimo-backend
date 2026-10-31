package br.com.luma.report.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TurnoverReportResponse(List<Row> items, Summary summary) {

    // totalCostCents: soma dos custos informados (null se nenhum); marginCents: só com os dois custos.
    public record Row(String code, String type, long valueCents, Instant soldAt, UUID sellerId, String sellerName,
            long daysToSell, long commissionCents, Long totalCostCents, Long marginCents) {
    }

    // marginCents soma só as peças com os dois custos; piecesWithoutFullCost diz quantas ficaram de fora.
    public record Summary(int soldCount, long soldValueCents, Long marginCents, int piecesWithoutFullCost) {
    }
}
