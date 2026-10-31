package br.com.luma.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.luma.consignment.service.ConsignmentApplicationService;
import br.com.luma.dashboard.dto.DashboardResponse;
import br.com.luma.report.dto.OverdueReportResponse;
import br.com.luma.report.dto.StalePiecesReportResponse;
import br.com.luma.report.repository.ReportQueryRepository;
import br.com.luma.report.repository.ReportQueryRepository.InHandTotals;
import br.com.luma.report.service.ReportApplicationService;
import br.com.luma.shared.PieceStage;
import br.com.luma.shared.dto.PageEnvelope;
import br.com.luma.shared.dto.PaginationRequest;

@ExtendWith(MockitoExtension.class)
class DashboardApplicationServiceTests {

    @Mock
    private ReportQueryRepository reportRepository;
    @Mock
    private ReportApplicationService reportService;
    @Mock
    private ConsignmentApplicationService consignmentService;

    private DashboardApplicationService service;

    @BeforeEach
    void setUp() {
        service = new DashboardApplicationService(reportRepository, reportService, consignmentService);
    }

    @Test
    void assemblesKpisFromTheSameRulesAsTheReports() {
        OverdueReportResponse.Row overdue = new OverdueReportResponse.Row(UUID.randomUUID(), "Ana", "11", 1, 2,
                20_000, 3);
        when(reportRepository.findInHandTotals()).thenReturn(new InHandTotals(5, 41_200));
        when(reportService.stalePieces(60)).thenReturn(new StalePiecesReportResponse(List.of(),
                new StalePiecesReportResponse.Summary(2, 23_390)));
        when(reportService.overdue()).thenReturn(new OverdueReportResponse(List.of(overdue)));
        when(reportService.averageTurnoverDays()).thenReturn(18);
        when(reportRepository.countPiecesByStage()).thenReturn(Map.of(PieceStage.ETIQUETADA, 5L,
                PieceStage.VENDIDA, 4L));
        when(consignmentService.list(eq("aberta"), isNull(), isNull(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(emptyPage());

        DashboardResponse response = service.get();

        assertThat(response.kpis()).isEqualTo(new DashboardResponse.Kpis(5, 41_200, 18, 2, 23_390, 1));
        assertThat(response.overdueSellers()).containsExactly(overdue);
    }

    @Test
    void stageCountsAlwaysHaveAllLowercaseKeysWithZeroDefaults() {
        when(reportRepository.findInHandTotals()).thenReturn(new InHandTotals(0, 0));
        when(reportService.stalePieces(60)).thenReturn(new StalePiecesReportResponse(List.of(),
                new StalePiecesReportResponse.Summary(0, 0)));
        when(reportService.overdue()).thenReturn(new OverdueReportResponse(List.of()));
        when(reportService.averageTurnoverDays()).thenReturn(null);
        when(reportRepository.countPiecesByStage()).thenReturn(Map.of(PieceStage.BANHO_OURO, 2L));
        when(consignmentService.list(eq("aberta"), isNull(), isNull(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(emptyPage());

        DashboardResponse response = service.get();

        assertThat(response.stageCounts()).containsExactly(
                Map.entry("fabrica", 0L), Map.entry("banho_ouro", 2L), Map.entry("etiquetada", 0L),
                Map.entry("consignacao", 0L), Map.entry("vendida", 0L), Map.entry("devolvida", 0L));
        assertThat(response.kpis().avgTurnoverDays()).isNull();
        assertThat(response.kpis().overdueSellers()).isZero();
    }

    @Test
    void openConsignmentsAreTheMostUrgentOpenLots() {
        when(reportRepository.findInHandTotals()).thenReturn(new InHandTotals(0, 0));
        when(reportService.stalePieces(60)).thenReturn(new StalePiecesReportResponse(List.of(),
                new StalePiecesReportResponse.Summary(0, 0)));
        when(reportService.overdue()).thenReturn(new OverdueReportResponse(List.of()));
        when(reportRepository.countPiecesByStage()).thenReturn(Map.of());
        ArgumentCaptor<PaginationRequest> pagination = ArgumentCaptor.forClass(PaginationRequest.class);
        when(consignmentService.list(eq("aberta"), isNull(), isNull(), pagination.capture())).thenReturn(emptyPage());

        service.get();

        assertThat(pagination.getValue().getPageSize()).isEqualTo(PaginationRequest.MAX_PAGE_SIZE);
        assertThat(pagination.getValue().getSort()).containsExactly("dueAt,asc");
    }

    private static <T> PageEnvelope<T> emptyPage() {
        return PageEnvelope.from(new org.springframework.data.domain.PageImpl<>(List.of()));
    }
}
