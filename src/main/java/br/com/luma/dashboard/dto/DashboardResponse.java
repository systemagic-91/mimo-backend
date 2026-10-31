package br.com.luma.dashboard.dto;

import java.util.List;
import java.util.Map;

import br.com.luma.consignment.dto.ConsignmentListItemResponse;
import br.com.luma.report.dto.OverdueReportResponse;

public record DashboardResponse(Kpis kpis, Map<String, Long> stageCounts,
        List<ConsignmentListItemResponse> openConsignments, List<OverdueReportResponse.Row> overdueSellers) {

    public record Kpis(long activeInHand, long activeInHandValueCents, Integer avgTurnoverDays, int staleCount,
            long staleValueCents, int overdueSellers) {
    }
}
