package br.com.luma.dashboard.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.luma.consignment.dto.ConsignmentListItemResponse;
import br.com.luma.consignment.service.ConsignmentApplicationService;
import br.com.luma.dashboard.dto.DashboardResponse;
import br.com.luma.report.dto.OverdueReportResponse;
import br.com.luma.report.dto.StalePiecesReportResponse;
import br.com.luma.report.repository.ReportQueryRepository;
import br.com.luma.report.repository.ReportQueryRepository.InHandTotals;
import br.com.luma.report.service.ReportApplicationService;
import br.com.luma.shared.PieceStage;
import br.com.luma.shared.dto.PaginationRequest;

@Service
public class DashboardApplicationService {

    static final int OPEN_CONSIGNMENTS_LIMIT = PaginationRequest.MAX_PAGE_SIZE;

    private final ReportQueryRepository reportRepository;
    private final ReportApplicationService reportService;
    private final ConsignmentApplicationService consignmentService;

    public DashboardApplicationService(ReportQueryRepository reportRepository, ReportApplicationService reportService,
            ConsignmentApplicationService consignmentService) {
        this.reportRepository = reportRepository;
        this.reportService = reportService;
        this.consignmentService = consignmentService;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('OWNER')")
    public DashboardResponse get() {
        InHandTotals inHand = reportRepository.findInHandTotals();
        StalePiecesReportResponse stale = reportService.stalePieces(ReportApplicationService.DEFAULT_STALE_DAYS);
        OverdueReportResponse overdue = reportService.overdue();

        DashboardResponse.Kpis kpis = new DashboardResponse.Kpis(inHand.pieces(), inHand.valueCents(),
                reportService.averageTurnoverDays(), stale.summary().count(), stale.summary().valueCents(),
                overdue.items().size());
        return new DashboardResponse(kpis, stageCounts(), openConsignments(), overdue.items());
    }

    private Map<String, Long> stageCounts() {
        Map<PieceStage, Long> counts = reportRepository.countPiecesByStage();
        Map<String, Long> result = new LinkedHashMap<>();
        for (PieceStage stage : PieceStage.values()) {
            result.put(stage.name().toLowerCase(Locale.ROOT), counts.getOrDefault(stage, 0L));
        }
        return result;
    }

    private List<ConsignmentListItemResponse> openConsignments() {
        PaginationRequest pagination = new PaginationRequest();
        pagination.setPageSize(OPEN_CONSIGNMENTS_LIMIT);
        pagination.setSort(List.of("dueAt,asc"));
        return consignmentService.list("aberta", null, null, pagination).items();
    }
}
