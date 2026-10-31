package br.com.luma.piece.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class PieceCosts {

    private PieceCosts() {
    }

    /** Soma dos custos informados; {@code null} quando nenhum foi informado (não é zero). */
    public static Long total(Long factoryCostCents, Long bathCostCents) {
        if (factoryCostCents == null && bathCostCents == null) {
            return null;
        }
        return (factoryCostCents == null ? 0 : factoryCostCents) + (bathCostCents == null ? 0 : bathCostCents);
    }

    /** Margem = venda − custo da fábrica − custo do banho − comissão; só existe com os dois custos informados. */
    public static Long margin(long saleValueCents, int commissionRate, Long factoryCostCents, Long bathCostCents) {
        if (factoryCostCents == null || bathCostCents == null) {
            return null;
        }
        return saleValueCents - factoryCostCents - bathCostCents - commission(saleValueCents, commissionRate);
    }

    // Mesmo arredondamento do ROUND(numeric) do PostgreSQL usado nos relatórios de comissão.
    public static long commission(long saleValueCents, int commissionRate) {
        return BigDecimal.valueOf(saleValueCents).multiply(BigDecimal.valueOf(commissionRate))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact();
    }
}
