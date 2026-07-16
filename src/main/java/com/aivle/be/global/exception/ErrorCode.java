package com.aivle.be.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    SIMULATION_NOT_FOUND(HttpStatus.NOT_FOUND, "SIMULATION_001", "존재하지 않는 시뮬레이션입니다."),
    ROBOT_NOT_FOUND(HttpStatus.NOT_FOUND, "ROBOT_001", "존재하지 않는 로봇입니다."),
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "EVENT_001","존재하지 않는 이벤트입니다."),
    TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "TASK_001", "존재하지 않는 작업입니다."),
    TASK_ALREADY_PROCESSED(HttpStatus.CONFLICT, "TASK_002", "이미 처리 중이거나 종료된 작업입니다."),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_001", "입력값이 올바르지 않습니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "USER_001", "이미 사용 중인 이메일입니다."),
    INTERNAL_SERVER_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "COMMON_002",
            "서버 내부 오류가 발생했습니다."
    );

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
