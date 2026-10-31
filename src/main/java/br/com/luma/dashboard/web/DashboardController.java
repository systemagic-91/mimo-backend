package br.com.luma.dashboard.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.luma.dashboard.dto.DashboardResponse;
import br.com.luma.dashboard.service.DashboardApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/dashboard")
@PreAuthorize("hasRole('OWNER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Dashboard", description = "KPIs e visão geral da operação (somente owner).")
public class DashboardController {

    private final DashboardApplicationService dashboardService;

    public DashboardController(DashboardApplicationService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    @Operation(summary = "KPIs, peças por etapa, lotes abertos e vendedoras em atraso")
    public DashboardResponse get() {
        return dashboardService.get();
    }
}
