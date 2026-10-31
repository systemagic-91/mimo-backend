package br.com.luma.seller.dto;

import java.time.LocalDate;
import java.util.List;

// Vendas do período agrupadas pelo prazo de pagamento do lote; paymentTermDays null = lote sem prazo informado.
public record PaymentScheduleResponse(List<Group> groups, long totalDueCents) {

    public record Group(Integer paymentTermDays, int soldCount, long amountDueCents, LocalDate firstDueDate,
            LocalDate lastDueDate) {
    }
}
