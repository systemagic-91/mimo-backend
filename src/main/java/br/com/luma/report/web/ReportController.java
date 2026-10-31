package br.com.luma.report.web;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.luma.report.dto.OverdueReportResponse;
import br.com.luma.report.dto.StalePiecesReportResponse;
import br.com.luma.report.dto.TurnoverReportResponse;
import br.com.luma.report.service.ReportApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/reports")
@PreAuthorize("hasRole('OWNER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Relatórios", description = "Giro, atrasos e estoque parado (somente owner).")
public class ReportController {

    private final ReportApplicationService reportService;

    public ReportController(ReportApplicationService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/turnover")
    @Operation(summary = "Vendas no período (datas locais inclusivas, até 366 dias) com dias para vender")
    public TurnoverReportResponse turnover(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.turnover(from, to);
    }

    @GetMapping("/overdue")
    @Operation(summary = "Vendedoras com lotes vencidos, peças e valor em risco")
    public OverdueReportResponse overdue() {
        return reportService.overdue();
    }

    @GetMapping("/stale-pieces")
    @Operation(summary = "Peças etiquetadas paradas no estoque há pelo menos `days` dias")
    public StalePiecesReportResponse stalePieces(
            @RequestParam(defaultValue = "" + ReportApplicationService.DEFAULT_STALE_DAYS) int days) {
        return reportService.stalePieces(days);
    }
}
