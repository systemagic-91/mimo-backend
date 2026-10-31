package br.com.luma.report.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TurnoverReportResponse(List<Row> items, Summary summary) {

    public record Row(String code, String type, long valueCents, Instant soldAt, UUID sellerId, String sellerName,
            long daysToSell) {
    }

    public record Summary(int soldCount, long soldValueCents) {
    }
}
