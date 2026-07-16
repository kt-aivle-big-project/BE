package com.aivle.be.auth.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordValidator implements ConstraintValidator<ValidPassword, String> {

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 24;

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            return false;
        }

        boolean hasLetter = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;

        for (char character : password.toCharArray()) {
            if (character < 33 || character > 126) {
                return false;
            }

            if (isAsciiLetter(character)) {
                hasLetter = true;
            } else if (Character.isDigit(character)) {
                hasDigit = true;
            } else {
                hasSpecial = true;
            }
        }

        int characterTypeCount = (hasLetter ? 1 : 0)
                + (hasDigit ? 1 : 0)
                + (hasSpecial ? 1 : 0);
        return characterTypeCount >= 2;
    }

    private boolean isAsciiLetter(char character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z');
    }
}
