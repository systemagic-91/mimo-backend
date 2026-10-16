package br.com.luma.seller.dto;

public record SellerCommissionResponse(long soldCount, long soldValueCents, long commissionCents) {
}