package br.com.luma.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import br.com.luma.report.dto.OverdueReportResponse;
import br.com.luma.report.dto.StalePiecesReportResponse;
import br.com.luma.report.dto.TurnoverReportResponse;
import br.com.luma.report.repository.ReportQueryRepository;
import br.com.luma.report.repository.ReportQueryRepository.OverdueSellerRow;
import br.com.luma.report.repository.ReportQueryRepository.SaleRow;
import br.com.luma.report.repository.ReportQueryRepository.StockRow;
import br.com.luma.setting.entity.SettingsEntity;
import br.com.luma.setting.repository.SettingsRepository;
import br.com.luma.shared.exception.ApiException;

@ExtendWith(MockitoExtension.class)
class ReportApplicationServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    @Mock
    private ReportQueryRepository reportRepository;
    @Mock
    private SettingsRepository settingsRepository;

    private ReportApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ReportApplicationService(reportRepository, settingsRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        useTimezone("America/Sao_Paulo");
    }

    @Test
    void turnoverUsesHalfOpenIntervalInStoreTimezoneAcrossMonthBoundary() {
        when(reportRepository.findSales(Instant.parse("2026-10-01T03:00:00Z"), Instant.parse("2026-11-01T03:00:00Z")))
                .thenReturn(List.of());

        TurnoverReportResponse response = service.turnover(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertThat(response.items()).isEmpty();
        assertThat(response.summary()).isEqualTo(new TurnoverReportResponse.Summary(0, 0));
    }

    @Test
    void turnoverFollowsTheConfiguredTimezone() {
        useTimezone("America/Manaus");
        when(reportRepository.findSales(Instant.parse("2026-10-30T04:00:00Z"), Instant.parse("2026-10-31T04:00:00Z")))
                .thenReturn(List.of());

        service.turnover(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 30));
    }

    @Test
    void turnoverCountsCalendarDaysAndSummarizes() {
        Instant from = Instant.parse("2026-10-01T03:00:00Z");
        Instant to = Instant.parse("2026-11-01T03:00:00Z");
        when(reportRepository.findSales(from, to)).thenReturn(List.of(
                sale("LU-0001", 10_000, Instant.parse("2026-10-02T02:30:00Z"), Instant.parse("2026-10-02T03:10:00Z")),
                sale("LU-0002", 25_000, Instant.parse("2026-10-05T13:00:00Z"), Instant.parse("2026-10-05T22:00:00Z")),
                sale("LU-0003", 5_000, Instant.parse("2026-09-01T15:00:00Z"), Instant.parse("2026-10-01T15:00:00Z"))));

        TurnoverReportResponse response = service.turnover(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertThat(response.items()).extracting(TurnoverReportResponse.Row::daysToSell).containsExactly(1L, 0L, 30L);
        assertThat(response.summary()).isEqualTo(new TurnoverReportResponse.Summary(3, 40_000));
    }

    @Test
    void turnoverRejectsInvertedOrTooLongPeriodsButAcceptsOneFullYear() {
        assertValidation(() -> service.turnover(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 1)), "period");
        assertValidation(() -> service.turnover(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 2)), "period");
        verifyNoInteractions(reportRepository);

        when(reportRepository.findSales(Instant.parse("2026-01-01T03:00:00Z"), Instant.parse("2027-01-02T03:00:00Z")))
                .thenReturn(List.of());
        service.turnover(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
    }

    @Test
    void overdueCountsDaysSinceTheOldestDueDateAndSortsByUrgency() {
        UUID ana = UUID.randomUUID();
        UUID bia = UUID.randomUUID();
        when(reportRepository.findOverdueSellers(NOW)).thenReturn(List.of(
                new OverdueSellerRow(ana, "Ana", "11", 1, 2, 20_000, Instant.parse("2026-10-30T02:59:59.999999Z")),
                new OverdueSellerRow(bia, "Bia", "12", 2, 3, 35_000, Instant.parse("2026-10-20T02:59:59.999999Z"))));

        OverdueReportResponse response = service.overdue();

        assertThat(response.items()).extracting(OverdueReportResponse.Row::sellerName).containsExactly("Bia", "Ana");
        assertThat(response.items()).extracting(OverdueReportResponse.Row::maxOverdueDays).containsExactly(11L, 1L);
        assertThat(response.items().get(0).valueAtRiskCents()).isEqualTo(35_000);
    }

    @Test
    void stalePiecesFilterByLocalLabelDateAndSummarize() {
        when(reportRepository.findLabeledPiecesLabeledBefore(Instant.parse("2026-09-01T03:00:00Z"))).thenReturn(List.of(
                stock("LU-0002", 8_000, Instant.parse("2026-08-31T15:00:00Z")),
                stock("LU-0001", 12_000, Instant.parse("2026-07-01T15:00:00Z"))));

        StalePiecesReportResponse response = service.stalePieces(60);

        assertThat(response.items()).extracting(StalePiecesReportResponse.Row::code)
                .containsExactly("LU-0001", "LU-0002");
        assertThat(response.items()).extracting(StalePiecesReportResponse.Row::daysStopped)
                .containsExactly(121L, 60L);
        assertThat(response.summary()).isEqualTo(new StalePiecesReportResponse.Summary(2, 20_000));
    }

    @Test
    void stalePiecesRejectsOutOfRangeDays() {
        assertValidation(() -> service.stalePieces(0), "days");
        assertValidation(() -> service.stalePieces(3651), "days");
        verifyNoInteractions(reportRepository);
    }

    @Test
    void averageTurnoverUsesTheLastNinetyDaysAndRoundsHalfUp() {
        Instant windowStart = Instant.parse("2026-08-01T03:00:00Z");
        when(reportRepository.findSales(windowStart, NOW.plusMillis(1))).thenReturn(List.of(
                sale("LU-0001", 1, Instant.parse("2026-10-01T15:00:00Z"), Instant.parse("2026-10-02T15:00:00Z")),
                sale("LU-0002", 1, Instant.parse("2026-10-01T15:00:00Z"), Instant.parse("2026-10-03T15:00:00Z"))));

        assertThat(service.averageTurnoverDays()).isEqualTo(2);
    }

    @Test
    void averageTurnoverIsNullWithoutSales() {
        when(reportRepository.findSales(Instant.parse("2026-08-01T03:00:00Z"), NOW.plusMillis(1)))
                .thenReturn(List.of());

        assertThat(service.averageTurnoverDays()).isNull();
    }

    private void useTimezone(String timezone) {
        SettingsEntity settings = new SettingsEntity();
        settings.setTimezone(timezone);
        lenient().when(settingsRepository.findFirstBySingletonKeyTrue()).thenReturn(Optional.of(settings));
    }

    private static SaleRow sale(String code, long value, Instant labeledAt, Instant soldAt) {
        return new SaleRow(code, "Brinco", value, soldAt, UUID.randomUUID(), "Ana", labeledAt);
    }

    private static StockRow stock(String code, long value, Instant labeledAt) {
        return new StockRow(UUID.randomUUID(), code, "Anel", value, labeledAt);
    }

    private static void assertValidation(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getCode()).isEqualTo("VALIDATION_ERROR");
            assertThat(exception.getFields()).containsKey(field);
        });
    }
}
