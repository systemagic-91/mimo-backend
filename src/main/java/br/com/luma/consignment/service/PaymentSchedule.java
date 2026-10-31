package br.com.luma.consignment.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import br.com.luma.piece.service.PieceCosts;

// Cada peça vendida vence em (dia da venda no fuso da loja + prazo); a vendedora repassa venda − comissão.
public final class PaymentSchedule {

    private PaymentSchedule() {
    }

    public static LocalDate dueDate(Instant soldAt, int paymentTermDays, ZoneId zone) {
        return soldAt.atZone(zone).toLocalDate().plusDays(paymentTermDays);
    }

    public static long amountDue(long saleValueCents, int commissionRate) {
        return saleValueCents - PieceCosts.commission(saleValueCents, commissionRate);
    }
}
