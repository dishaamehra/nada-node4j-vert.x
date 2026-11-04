package com.nada.connections.model;

import java.security.SecureRandom;
import java.util.regex.Pattern;

// Creates and checks person ids: 32 random bits written as 8 lowercase hex characters, e.g. "3fa9c012".
public final class PersonId {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern VALID_ID = Pattern.compile("[0-9a-f]{8}");

    private PersonId() {
    }

    public static String generate() {
        // %08x prints the 32 bits of the int as exactly 8 hex digits, even when the int is negative.
        return String.format("%08x", RANDOM.nextInt());
    }

    public static boolean isValid(String text) {
        return text != null && VALID_ID.matcher(text).matches();
    }
}
