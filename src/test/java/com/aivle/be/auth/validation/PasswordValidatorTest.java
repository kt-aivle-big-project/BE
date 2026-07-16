package com.aivle.be.auth.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordValidatorTest {

    private final PasswordValidator validator = new PasswordValidator();

    @Test
    void acceptsPasswordsContainingAtLeastTwoCharacterTypes() {
        assertThat(validator.isValid("password123", null)).isTrue();
        assertThat(validator.isValid("password!", null)).isTrue();
        assertThat(validator.isValid("1234567!", null)).isTrue();
    }

    @Test
    void rejectsPasswordWithOnlyOneCharacterType() {
        assertThat(validator.isValid("password", null)).isFalse();
        assertThat(validator.isValid("12345678", null)).isFalse();
        assertThat(validator.isValid("!!!!!!!!", null)).isFalse();
    }

    @Test
    void rejectsInvalidLengthOrWhitespace() {
        assertThat(validator.isValid("Pass1!", null)).isFalse();
        assertThat(validator.isValid("Password1234567890123456!", null)).isFalse();
        assertThat(validator.isValid("Password 123!", null)).isFalse();
        assertThat(validator.isValid(null, null)).isFalse();
    }
}
