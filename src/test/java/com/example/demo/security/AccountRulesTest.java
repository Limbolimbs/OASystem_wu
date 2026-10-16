package com.example.demo.security;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccountRulesTest {
    @ParameterizedTest
    @ValueSource(strings = {"Abcdefgh1234", "abcdefgh123!", "ABCDEFGH123!", "Abcdefghijk!", "Abcdefg1234!", " Abcdefgh1234 "})
    void validatesLengthAndAnyThreeCategories(String password) {
        assertDoesNotThrow(() -> AccountRules.validateNewPassword(password));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Aa123456789", "abcdefghijkl", "abcdefgh1234", "ABCDEFGHIJK!", "Aa12345678\n!", "Aa123456789\u0000", "Ab123456789\u2028", "Ab123456789\ud800"})
    void rejectsWeakOrNonPrintablePasswords(String password) {
        assertThrows(IllegalArgumentException.class, () -> AccountRules.validateNewPassword(password));
    }

    @Test void countsUnicodeCodePointsAndDoesNotTruncate() {
        assertDoesNotThrow(() -> AccountRules.validateNewPassword("Aa1" + "🙂".repeat(125)));
        assertThrows(IllegalArgumentException.class, () -> AccountRules.validateNewPassword("Aa1" + "🙂".repeat(126)));
    }

    @Test void emptyOrNullValuesAndConfirmationAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AccountRules.validateNewPassword(null));
        assertThrows(IllegalArgumentException.class, () -> AccountRules.validateLoginId(null));
        assertThrows(IllegalArgumentException.class, () -> AccountRules.validateLoginId(" "));
        assertThrows(IllegalArgumentException.class, () -> AccountRules.validateLoginId("E".repeat(51)));
        assertThrows(IllegalArgumentException.class, () -> AccountRules.validateLoginId("EMP\n001"));
        assertThrows(IllegalArgumentException.class, () -> AccountRules.validatePasswordConfirmation("Abcdefgh1234", ""));
        assertThrows(IllegalArgumentException.class, () -> AccountRules.validatePasswordConfirmation("Abcdefgh1234", "Abcdefgh1234 "));
        assertDoesNotThrow(() -> AccountRules.validateLoginId(" 000123 "));
        assertDoesNotThrow(() -> AccountRules.validateLoginId("tanaka"));
    }
}
