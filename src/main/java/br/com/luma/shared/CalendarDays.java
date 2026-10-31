package br.com.luma.shared;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

public final class CalendarDays {

    private CalendarDays() {
    }

    public static long between(Instant from, Instant to, ZoneId zone) {
        return ChronoUnit.DAYS.between(from.atZone(zone).toLocalDate(), to.atZone(zone).toLocalDate());
    }

    public static long elapsed(Instant from, Instant to, ZoneId zone) {
        return Math.max(0, between(from, to, zone));
    }
}
