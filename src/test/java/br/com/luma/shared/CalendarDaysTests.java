package br.com.luma.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class CalendarDaysTests {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    @Test
    void countsDateChangesInTheStoreZoneNotFullDaysOfTwentyFourHours() {
        Instant lateEvening = Instant.parse("2026-10-02T02:30:00Z");
        Instant nextMorning = Instant.parse("2026-10-02T03:10:00Z");

        assertThat(CalendarDays.between(lateEvening, nextMorning, SAO_PAULO)).isEqualTo(1);
        assertThat(CalendarDays.between(lateEvening, nextMorning, ZoneId.of("UTC"))).isZero();
    }

    @Test
    void sameLocalDayIsZeroEvenWhenAlmostTwentyFourHoursApart() {
        Instant startOfDay = Instant.parse("2026-10-01T03:00:00Z");
        Instant endOfDay = Instant.parse("2026-10-02T02:59:59Z");

        assertThat(CalendarDays.between(startOfDay, endOfDay, SAO_PAULO)).isZero();
    }

    @Test
    void handlesMonthAndYearBoundaries() {
        assertThat(CalendarDays.between(Instant.parse("2026-10-31T15:00:00Z"), Instant.parse("2026-11-01T15:00:00Z"),
                SAO_PAULO)).isEqualTo(1);
        assertThat(CalendarDays.between(Instant.parse("2026-12-31T15:00:00Z"), Instant.parse("2027-01-01T15:00:00Z"),
                SAO_PAULO)).isEqualTo(1);
    }

    @Test
    void elapsedNeverGoesNegative() {
        Instant earlier = Instant.parse("2026-10-01T15:00:00Z");
        Instant later = Instant.parse("2026-10-05T15:00:00Z");

        assertThat(CalendarDays.between(later, earlier, SAO_PAULO)).isEqualTo(-4);
        assertThat(CalendarDays.elapsed(later, earlier, SAO_PAULO)).isZero();
    }
}
