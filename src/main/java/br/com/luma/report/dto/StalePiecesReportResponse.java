package br.com.luma.report.dto;

import java.util.List;
import java.util.UUID;

public record StalePiecesReportResponse(List<Row> items, Summary summary) {

    public record Row(UUID id, String code, String type, long valueCents, long daysStopped) {
    }

    public record Summary(int count, long valueCents) {
    }
}
