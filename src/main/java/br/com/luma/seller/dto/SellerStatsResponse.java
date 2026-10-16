package br.com.luma.seller.dto;

public record SellerStatsResponse(long inHand, long inHandValueCents, long totalSoldCount,
        long totalSoldValueCents, long totalCommissionCents) {
}