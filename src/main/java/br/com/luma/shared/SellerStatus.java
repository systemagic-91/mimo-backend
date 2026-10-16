package br.com.luma.shared;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum SellerStatus {
    ATIVA,
    INATIVA;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static SellerStatus fromJson(String value) {
        return SellerStatus.valueOf(value.toUpperCase(Locale.ROOT));
    }
}