package com.aivle.be.laro.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;

import java.util.StringJoiner;

/**
 * Keeps the public LARO mapping error code while preserving the exact failed
 * contract field for command-cycle diagnostics and Human Review.
 */
public final class LaroPlanMappingException extends BusinessException {

    private final String diagnosticMessage;

    public LaroPlanMappingException(String reason, Object... context) {
        super(ErrorCode.LARO_PLAN_MAPPING_FAILED);
        StringJoiner values = new StringJoiner(", ");
        for (int index = 0; index + 1 < context.length; index += 2) {
            values.add(String.valueOf(context[index]) + "=" + String.valueOf(context[index + 1]));
        }
        diagnosticMessage = "AI 계획 매핑 실패 [reason=" + reason
                + (values.length() == 0 ? "" : ", " + values)
                + "]";
    }

    @Override
    public String getMessage() {
        return diagnosticMessage;
    }
}
