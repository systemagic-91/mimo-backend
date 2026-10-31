package br.com.luma.report.dto;

import java.util.List;
import java.util.UUID;

public record OverdueReportResponse(List<Row> items) {

    public record Row(UUID sellerId, String sellerName, String phone, int lateLots, long piecesInHand,
            long valueAtRiskCents, long maxOverdueDays) {
    }
}
