package br.com.luma.shared;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Role {
    OWNER,
    STAFF;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static Role fromJson(String value) {
        return Role.valueOf(value.toUpperCase(Locale.ROOT));
    }
}