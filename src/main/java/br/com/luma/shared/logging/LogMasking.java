package br.com.luma.shared.logging;

/** Mascaramento de dados pessoais nos logs, num formato só. */
public final class LogMasking {

    private LogMasking() {
    }

    /** {@code luma@email.com} vira {@code l***@email.com}; um valor que não parece e-mail vira {@code ***}. */
    public static String email(String email) {
        if (email == null || email.isBlank()) return "***";
        String trimmed = email.trim();
        int at = trimmed.indexOf('@');
        if (at < 1 || at == trimmed.length() - 1) return "***";
        return trimmed.charAt(0) + "***" + trimmed.substring(at);
    }
}
