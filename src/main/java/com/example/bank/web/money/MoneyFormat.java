package com.example.bank.web.money;

import java.util.regex.Pattern;

/**
 * Conversión entre centavos (long) y el formato wire "1500.00".
 *
 * Reglas:
 *   - Acepta exactamente 2 decimales. "10.005" se rechaza.
 *   - Acepta signo negativo? No. El monto debe ser > 0; el signo se valida
 *     en el DTO, aquí solo parseamos forma.
 *   - Acepta notación científica? No. "1e3" se rechaza.
 *   - Acepta ceros a la izquierda? No. "01500.00" se rechaza.
 */
public final class MoneyFormat {

    private static final Pattern DECIMAL_2 = Pattern.compile("^(0|[1-9][0-9]*)\\.[0-9]{2}$");
    private MoneyFormat() {}
    public static boolean isValid(String raw) {
        return raw != null && DECIMAL_2.matcher(raw).matches();
    }

    /**
     * Convierte "1500.00" a 150000 centavos.
     * Lanza IllegalArgumentException si la forma es inválida.
     */
    public static long toCents(String raw) {
        if (!isValid(raw)) {
            throw new IllegalArgumentException("invalid money format: " + raw);
        }
        int dot = raw.indexOf('.');
        String intPart = raw.substring(0, dot);
        String decPart = raw.substring(dot + 1);
        long units = Long.parseLong(intPart);
        long cents = Long.parseLong(decPart);
        return Math.addExact(Math.multiplyExact(units, 100L), cents);
    }

    /**
     * Convierte 150000 centavos a "1500.00".
     */
    public static String fromCents(long cents) {
        long units = cents / 100;
        long rem = Math.abs(cents % 100);
        return units + "." + (rem < 10 ? "0" + rem : Long.toString(rem));
    }
}