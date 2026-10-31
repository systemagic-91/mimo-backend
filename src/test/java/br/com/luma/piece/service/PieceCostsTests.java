package br.com.luma.piece.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PieceCostsTests {

    @Test
    void totalIsNullOnlyWhenNoCostWasInformed() {
        assertThat(PieceCosts.total(null, null)).isNull();
        assertThat(PieceCosts.total(3_000L, null)).isEqualTo(3_000L);
        assertThat(PieceCosts.total(null, 800L)).isEqualTo(800L);
        assertThat(PieceCosts.total(3_000L, 800L)).isEqualTo(3_800L);
        assertThat(PieceCosts.total(0L, 0L)).isZero();
    }

    @Test
    void marginNeedsBothCosts() {
        assertThat(PieceCosts.margin(10_000, 30, null, null)).isNull();
        assertThat(PieceCosts.margin(10_000, 30, 3_000L, null)).isNull();
        assertThat(PieceCosts.margin(10_000, 30, null, 800L)).isNull();
        assertThat(PieceCosts.margin(10_000, 30, 3_000L, 800L)).isEqualTo(10_000 - 3_000 - 800 - 3_000);
    }

    @Test
    void marginCanBeNegativeAndZeroCostsCount() {
        assertThat(PieceCosts.margin(1_000, 40, 900L, 200L)).isEqualTo(1_000 - 900 - 200 - 400);
        assertThat(PieceCosts.margin(1_000, 25, 0L, 0L)).isEqualTo(750);
    }

    @Test
    void commissionRoundsHalfUpLikeTheDatabase() {
        assertThat(PieceCosts.commission(1_001, 25)).isEqualTo(250);
        assertThat(PieceCosts.commission(1_002, 25)).isEqualTo(251);
        assertThat(PieceCosts.commission(12_345, 35)).isEqualTo(4_321);
    }
}
