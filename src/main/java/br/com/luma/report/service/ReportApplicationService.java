package br.com.luma.report.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.luma.report.dto.OverdueReportResponse;
import br.com.luma.report.dto.StalePiecesReportResponse;
import br.com.luma.piece.service.PieceCosts;
import br.com.luma.report.dto.TurnoverReportResponse;
import br.com.luma.report.repository.ReportQueryRepository;
import br.com.luma.report.repository.ReportQueryRepository.SaleRow;
import br.com.luma.setting.repository.SettingsRepository;
import br.com.luma.shared.CalendarDays;
import br.com.luma.shared.exception.ApiException;

@Service
public class ReportApplicationService {

    public static final int DEFAULT_STALE_DAYS = 60;
    static final int MAX_TURNOVER_PERIOD_DAYS = 366;
    static final int MAX_STALE_DAYS = 3650;
    static final int TURNOVER_WINDOW_DAYS = 90;

    private final ReportQueryRepository reportRepository;
    private final SettingsRepository settingsRepository;
    private final Clock clock;

    public ReportApplicationService(ReportQueryRepository reportRepository, SettingsRepository settingsRepository,
            Clock clock) {
        this.reportRepository = reportRepository;
        this.settingsRepository = settingsRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('OWNER')")
    public TurnoverReportResponse turnover(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw validation("period", "A data inicial deve ser anterior ou igual à final.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_TURNOVER_PERIOD_DAYS) {
            throw validation("period", "O período deve ter no máximo " + MAX_TURNOVER_PERIOD_DAYS + " dias.");
        }
        ZoneId zone = storeZone();
        List<TurnoverReportResponse.Row> rows = reportRepository
                .findSales(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant())
                .stream()
                .map(sale -> new TurnoverReportResponse.Row(sale.code(), sale.type(), sale.valueCents(),
                        sale.soldAt(), sale.sellerId(), sale.sellerName(), daysToSell(sale, zone),
                        PieceCosts.commission(sale.valueCents(), sale.commissionRate()),
                        PieceCosts.total(sale.factoryCostCents(), sale.bathCostCents()),
                        PieceCosts.margin(sale.valueCents(), sale.commissionRate(), sale.factoryCostCents(),
                                sale.bathCostCents())))
                .toList();
        long soldValue = rows.stream().mapToLong(TurnoverReportResponse.Row::valueCents).sum();
        List<Long> margins = rows.stream().map(TurnoverReportResponse.Row::marginCents)
                .filter(java.util.Objects::nonNull).toList();
        Long margin = margins.isEmpty() ? null : margins.stream().mapToLong(Long::longValue).sum();
        return new TurnoverReportResponse(rows, new TurnoverReportResponse.Summary(rows.size(), soldValue, margin,
                rows.size() - margins.size()));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('OWNER')")
    public OverdueReportResponse overdue() {
        ZoneId zone = storeZone();
        Instant now = clock.instant();
        List<OverdueReportResponse.Row> rows = reportRepository.findOverdueSellers(now).stream()
                .map(seller -> new OverdueReportResponse.Row(seller.sellerId(), seller.sellerName(), seller.phone(),
                        seller.lateLots(), seller.piecesInHand(), seller.valueAtRiskCents(),
                        CalendarDays.elapsed(seller.oldestDueAt(), now, zone)))
                .sorted(Comparator.comparingLong(OverdueReportResponse.Row::maxOverdueDays).reversed()
                        .thenComparing(OverdueReportResponse.Row::sellerName))
                .toList();
        return new OverdueReportResponse(rows);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('OWNER')")
    public StalePiecesReportResponse stalePieces(int days) {
        if (days < 1 || days > MAX_STALE_DAYS) {
            throw validation("days", "days deve estar entre 1 e " + MAX_STALE_DAYS + ".");
        }
        ZoneId zone = storeZone();
        Instant now = clock.instant();
        LocalDate today = now.atZone(zone).toLocalDate();
        Instant labeledBefore = today.minusDays(days - 1L).atStartOfDay(zone).toInstant();
        List<StalePiecesReportResponse.Row> rows = reportRepository.findLabeledPiecesLabeledBefore(labeledBefore)
                .stream()
                .map(piece -> new StalePiecesReportResponse.Row(piece.id(), piece.code(), piece.type(),
                        piece.valueCents(), CalendarDays.elapsed(piece.labeledAt(), now, zone)))
                .sorted(Comparator.comparingLong(StalePiecesReportResponse.Row::daysStopped).reversed()
                        .thenComparing(StalePiecesReportResponse.Row::code))
                .toList();
        long value = rows.stream().mapToLong(StalePiecesReportResponse.Row::valueCents).sum();
        return new StalePiecesReportResponse(rows, new StalePiecesReportResponse.Summary(rows.size(), value));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('OWNER')")
    public Integer averageTurnoverDays() {
        ZoneId zone = storeZone();
        Instant now = clock.instant();
        Instant from = now.atZone(zone).toLocalDate().minusDays(TURNOVER_WINDOW_DAYS).atStartOfDay(zone).toInstant();
        List<SaleRow> sales = reportRepository.findSales(from, now.plusMillis(1));
        if (sales.isEmpty()) {
            return null;
        }
        double average = sales.stream().mapToLong(sale -> daysToSell(sale, zone)).average().orElseThrow();
        return Math.toIntExact(Math.round(average));
    }

    private ZoneId storeZone() {
        return settingsRepository.findFirstBySingletonKeyTrue()
                .map(settings -> ZoneId.of(settings.getTimezone()))
                .orElseThrow(() -> new IllegalStateException("Store settings are missing"));
    }

    private static long daysToSell(SaleRow sale, ZoneId zone) {
        return CalendarDays.elapsed(sale.labeledAt(), sale.soldAt(), zone);
    }

    private static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, Map.of(field, List.of(message)));
    }
}
