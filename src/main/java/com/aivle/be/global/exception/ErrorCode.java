package com.aivle.be.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    INVALID_SCENARIO_CONFIG(HttpStatus.BAD_REQUEST, "SIMULATION_RUN_007", "랜덤 시나리오 설정이 올바르지 않습니다."),
    TASK_SIMULATION_RUN_MISMATCH(HttpStatus.BAD_REQUEST, "SIMULATION_RUN_008", "작업과 시뮬레이션 실행의 창고가 일치하지 않습니다."),
    TASK_ROBOT_NOT_PARTICIPANT(HttpStatus.CONFLICT, "SIMULATION_RUN_009", "시뮬레이션 실행에 참여하지 않은 로봇입니다."),
    TASK_REQUIRES_RUNNING_SIMULATION(HttpStatus.CONFLICT, "SIMULATION_RUN_010", "실행 중인 시뮬레이션의 작업만 시작할 수 있습니다."),

    SIMULATION_RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "SIMULATION_RUN_001", "시뮬레이션 실행을 찾을 수 없습니다."),
    INVALID_SIMULATION_RUN_TRANSITION(HttpStatus.CONFLICT, "SIMULATION_RUN_002", "허용되지 않는 시뮬레이션 상태 변경입니다."),
    NO_AVAILABLE_ROBOTS(HttpStatus.CONFLICT, "SIMULATION_RUN_003", "시뮬레이션에 참여할 수 있는 대기 로봇이 없습니다."),
    SIMULATION_RUN_ALREADY_ACTIVE(HttpStatus.CONFLICT, "SIMULATION_RUN_004", "해당 창고에서 이미 실행 중인 시뮬레이션이 있습니다."),
    SIMULATION_RUN_NOT_RUNNING(HttpStatus.CONFLICT, "SIMULATION_RUN_005", "실행 중인 시뮬레이션에서만 로봇 상태를 갱신할 수 있습니다."),
    ROBOT_NOT_IN_SIMULATION_RUN(HttpStatus.CONFLICT, "SIMULATION_RUN_006", "해당 시뮬레이션에 참여하지 않은 로봇입니다."),

    ROBOT_STATE_STORE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "ROBOT_STATE_007", "로봇 상태 저장소에 연결할 수 없습니다."),
    ROBOT_STATE_DATA_CORRUPTED(HttpStatus.INTERNAL_SERVER_ERROR, "ROBOT_STATE_008", "저장된 로봇 상태 데이터가 올바르지 않습니다."),

    WAREHOUSE_NOT_FOUND(HttpStatus.NOT_FOUND, "WAREHOUSE_001", "존재하지 않는 창고입니다."),
    ROBOT_STATE_NOT_FOUND(HttpStatus.NOT_FOUND, "ROBOT_STATE_001", "로봇의 현재 상태를 찾을 수 없습니다."),
    NODE_NOT_FOUND(HttpStatus.NOT_FOUND, "ROBOT_STATE_002", "존재하지 않는 창고 노드입니다."),
    INVALID_ROBOT_LOCATION(HttpStatus.BAD_REQUEST, "ROBOT_STATE_003", "로봇과 현재 노드의 창고가 일치하지 않습니다."),
    INVALID_ROBOT_STATE_TRANSITION(HttpStatus.CONFLICT, "ROBOT_STATE_004", "허용되지 않는 로봇 상태 변경입니다."),
    INVALID_ROBOT_TASK(HttpStatus.CONFLICT, "ROBOT_STATE_005", "현재 작업이 해당 로봇에 할당된 작업과 일치하지 않습니다."),
    STALE_ROBOT_STATE(HttpStatus.CONFLICT, "ROBOT_STATE_006", "기존 상태보다 오래된 상태 정보입니다."),

    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_001", "이메일 또는 비밀번호가 올바르지 않습니다."),
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "AUTH_002", "로그인 실패 횟수를 초과하여 계정이 일시 잠겼습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_003", "유효하지 않은 인증 토큰입니다."),

    SIMULATION_NOT_FOUND(HttpStatus.NOT_FOUND, "SIMULATION_001", "존재하지 않는 시뮬레이션입니다."),

    ROBOT_NOT_FOUND(HttpStatus.NOT_FOUND, "ROBOT_001", "존재하지 않는 로봇입니다."),
    ROBOT_NOT_AVAILABLE(HttpStatus.CONFLICT, "ROBOT_004", "이미 작업중인 로봇입니다."),

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
