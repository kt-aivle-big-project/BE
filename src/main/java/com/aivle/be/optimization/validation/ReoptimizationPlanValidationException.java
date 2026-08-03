package com.aivle.be.optimization.validation;

import com.aivle.be.global.exception.ErrorCode;

public class ReoptimizationPlanValidationException
        extends RuntimeException {

    private final ErrorCode errorCode;

    public ReoptimizationPlanValidationException(
            ErrorCode errorCode,
            String message
    ) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
