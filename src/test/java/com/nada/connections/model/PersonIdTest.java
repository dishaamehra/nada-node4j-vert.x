package com.nada.connections.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PersonIdTest {

    @Test
    void generate_alwaysGivesEightLowercaseHexCharacters() {
        for (int attempt = 0; attempt < 1000; attempt++) {
            assertTrue(PersonId.isValid(PersonId.generate()));
        }
    }

    @Test
    void isValid_acceptsEightLowercaseHexCharacters() {
        assertTrue(PersonId.isValid("3fa9c012"));
        assertTrue(PersonId.isValid("00000000"));
    }

    @Test
    void isValid_rejectsAnythingElse() {
        assertFalse(PersonId.isValid(null));
        assertFalse(PersonId.isValid(""));
        assertFalse(PersonId.isValid("1"));
        assertFalse(PersonId.isValid("3FA9C012"));
        assertFalse(PersonId.isValid("3fa9c01"));
        assertFalse(PersonId.isValid("3fa9c0123"));
        assertFalse(PersonId.isValid("3fa9c01g"));
    }
}
