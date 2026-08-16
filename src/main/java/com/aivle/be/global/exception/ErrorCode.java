package com.aivle.be.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    ROBOT_SPEC_NOT_FOUND(HttpStatus.NOT_FOUND, "ROBOT_SPEC_001", "로봇 사양을 찾을 수 없습니다."),
    DUPLICATE_ROBOT_CODE(HttpStatus.CONFLICT, "ROBOT_SPEC_002", "이미 사용 중인 로봇 모델 코드입니다."),
    ROBOT_SPEC_IN_USE(HttpStatus.CONFLICT, "ROBOT_SPEC_003", "로봇이 사용 중인 사양은 수정하거나 삭제할 수 없습니다."),

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
    LOW_BATTERY_EVENT_NOT_AVAILABLE(
            HttpStatus.CONFLICT,
            "SIMULATION_RUN_041",
            "배터리 부족 이벤트를 적용할 작업 중 AI 로봇이 없거나 이미 이벤트가 처리 중입니다."
    ),

    ROBOT_STATE_STORE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "ROBOT_STATE_007", "로봇 상태 저장소에 연결할 수 없습니다."),
    ROBOT_STATE_DATA_CORRUPTED(HttpStatus.INTERNAL_SERVER_ERROR, "ROBOT_STATE_008", "저장된 로봇 상태 데이터가 올바르지 않습니다."),

    SHARED_WAREHOUSE_READ_ONLY(HttpStatus.FORBIDDEN, "WAREHOUSE_002", "공용 창고는 수정하거나 삭제할 수 없습니다."),
    WAREHOUSE_NODE_IN_ACTIVE_TASK(
            HttpStatus.CONFLICT,
            "WAREHOUSE_003",
            "진행 가능한 시뮬레이션 작업이 사용하는 노드는 제거할 수 없습니다. 해당 시뮬레이션을 중지한 뒤 다시 저장해 주세요."
    ),
    WAREHOUSE_NOT_FOUND(HttpStatus.NOT_FOUND, "WAREHOUSE_001", "존재하지 않는 창고입니다."),
    WAREHOUSE_NOT_TEMPLATE(HttpStatus.BAD_REQUEST, "WAREHOUSE_004", "공용 템플릿 창고가 아닙니다."),
    TEMPLATE_WAREHOUSE_NOT_EXECUTABLE(
            HttpStatus.FORBIDDEN,
            "WAREHOUSE_005",
            "공유 템플릿 창고에서는 시뮬레이션을 실행할 수 없습니다."
    ),
    ROBOT_STATE_NOT_FOUND(HttpStatus.NOT_FOUND, "ROBOT_STATE_001", "로봇의 현재 상태를 찾을 수 없습니다."),
    NODE_NOT_FOUND(HttpStatus.NOT_FOUND, "ROBOT_STATE_002", "존재하지 않는 창고 노드입니다."),
    INVALID_ROBOT_LOCATION(HttpStatus.BAD_REQUEST, "ROBOT_STATE_003", "로봇과 현재 노드의 창고가 일치하지 않습니다."),
    INVALID_ROBOT_STATE_TRANSITION(HttpStatus.CONFLICT, "ROBOT_STATE_004", "허용되지 않는 로봇 상태 변경입니다."),
    INVALID_ROBOT_TASK(HttpStatus.CONFLICT, "ROBOT_STATE_005", "현재 작업이 해당 로봇에 할당된 작업과 일치하지 않습니다."),
    STALE_ROBOT_STATE(HttpStatus.CONFLICT, "ROBOT_STATE_006", "기존 상태보다 오래된 상태 정보입니다."),

    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_001", "이메일 또는 비밀번호가 올바르지 않습니다."),
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "AUTH_002", "로그인 실패 횟수를 초과하여 계정이 일시 잠겼습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_003", "유효하지 않은 인증 토큰입니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_004", "유효하지 않은 Refresh Token입니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUTH_005", "접근 권한이 없습니다."),

    BOARD_POST_NOT_FOUND(HttpStatus.NOT_FOUND, "BOARD_POST_001", "게시글을 찾을 수 없습니다."),

    SIMULATION_NOT_FOUND(HttpStatus.NOT_FOUND, "SIMULATION_001", "존재하지 않는 시뮬레이션입니다."),

    ROBOT_NOT_FOUND(HttpStatus.NOT_FOUND, "ROBOT_001", "존재하지 않는 로봇입니다."),
    ROBOT_NOT_AVAILABLE(HttpStatus.CONFLICT, "ROBOT_004", "이미 작업중인 로봇입니다."),
    ROBOT_IN_ACTIVE_USE(HttpStatus.CONFLICT, "ROBOT_005", "활성 시뮬레이션 또는 진행 중인 작업에서 사용 중인 로봇입니다."),

    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "EVENT_001","존재하지 않는 이벤트입니다."),

    TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "TASK_001", "존재하지 않는 작업입니다."),
    TASK_ALREADY_PROCESSED(HttpStatus.CONFLICT, "TASK_002", "이미 처리 중이거나 종료된 작업입니다."),

    SCENARIO_NOT_FOUND(HttpStatus.NOT_FOUND, "SCENARIO_001", "존재하지 않는 시나리오입니다."),
    DUPLICATE_SCENARIO_CODE(HttpStatus.CONFLICT, "SCENARIO_002", "이미 사용 중인 시나리오 코드입니다."),
    SCENARIO_WAREHOUSE_MISMATCH(HttpStatus.BAD_REQUEST, "SCENARIO_003", "시나리오와 시뮬레이션의 창고가 일치하지 않습니다."),

    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "PRODUCT_001", "존재하지 않는 품목입니다."),
    DUPLICATE_PRODUCT_CODE(HttpStatus.CONFLICT, "PRODUCT_002", "이미 사용 중인 품목 코드입니다."),

    INVALID_INBOUND_RATIO(HttpStatus.BAD_REQUEST, "SIMULATION_RUN_011", "입고 품목 구성 비율의 합계는 100%여야 합니다."),
    INVALID_SIMULATION_SPEED(HttpStatus.BAD_REQUEST, "SIMULATION_RUN_012", "허용되지 않는 실행 배속입니다."),
    REPLANNING_STOP_TIMEOUT(
            HttpStatus.REQUEST_TIMEOUT,
            "SIMULATION_RUN_013",
            "재계획을 위한 로봇 안전 정지가 제한 시간 내 완료되지 않았습니다."
    ),
    REOPTIMIZATION_AI_FAILED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "SIMULATION_RUN_014",
            "AI 재계획 요청을 처리하지 못했습니다."
    ),
    REOPTIMIZATION_ALREADY_IN_PROGRESS(
            HttpStatus.CONFLICT,
            "SIMULATION_RUN_015",
            "해당 시뮬레이션 실행의 재계획이 이미 진행 중입니다."
    ),
    REOPTIMIZATION_PLAN_APPLICATION_NOT_IMPLEMENTED(
            HttpStatus.NOT_IMPLEMENTED,
            "SIMULATION_RUN_016",
            "AI 재계획 응답의 Runtime 적용은 아직 구현되지 않았습니다."
    ),
    REOPTIMIZATION_RESPONSE_CORRELATION_MISMATCH(
            HttpStatus.BAD_GATEWAY,
            "SIMULATION_RUN_017",
            "AI 재계획 응답의 요청 상관키가 일치하지 않습니다."
    ),
    REOPTIMIZATION_PLAN_INFEASIBLE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "SIMULATION_RUN_018",
            "AI가 실행 가능한 재계획을 찾지 못했습니다."
    ),
    MOCK_AI_PLAN_NOT_CONFIGURED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "SIMULATION_RUN_019",
            "Mock AI 재계획 fixture가 설정되지 않았습니다."
    ),
    MOCK_AI_PLAN_INVALID(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "SIMULATION_RUN_020",
            "Mock AI 재계획 fixture가 현재 요청과 일치하지 않거나 올바르지 않습니다."
    ),
    REOPTIMIZATION_PLAN_CONTRACT_INVALID(
            HttpStatus.BAD_GATEWAY,
            "SIMULATION_RUN_021",
            "AI 재계획 응답이 시간 또는 작업 진행 단계 계약을 위반했습니다."
    ),
    REOPTIMIZATION_PLAN_TASK_COVERAGE_INVALID(
            HttpStatus.BAD_GATEWAY,
            "SIMULATION_RUN_022",
            "AI 재계획 응답의 작업 범위가 남은 작업과 일치하지 않습니다."
    ),
    REOPTIMIZATION_PLAN_ROBOT_INVALID(
            HttpStatus.BAD_GATEWAY,
            "SIMULATION_RUN_023",
            "AI 재계획 응답이 참가하지 않았거나 사용할 수 없는 로봇을 참조합니다."
    ),
    REOPTIMIZATION_PLAN_SEQUENCE_INVALID(
            HttpStatus.BAD_GATEWAY,
            "SIMULATION_RUN_024",
            "AI 재계획 응답의 로봇별 작업 순서가 올바르지 않습니다."
    ),
    REOPTIMIZATION_PLAN_PATH_INVALID(
            HttpStatus.BAD_GATEWAY,
            "SIMULATION_RUN_025",
            "AI 재계획 응답의 경로가 창고 그래프와 일치하지 않습니다."
    ),
    REOPTIMIZATION_PLAN_BLOCKED_EDGE(
            HttpStatus.BAD_GATEWAY,
            "SIMULATION_RUN_026",
            "AI 재계획 응답이 차단된 edge를 통과합니다."
    ),
    REOPTIMIZATION_PLAN_CONFLICT(
            HttpStatus.BAD_GATEWAY,
            "SIMULATION_RUN_027",
            "AI 재계획 응답에 로봇 간 노드 또는 edge 시간 충돌이 있습니다."
    ),
    REOPTIMIZATION_PLAN_STALE(
            HttpStatus.CONFLICT,
            "SIMULATION_RUN_028",
            "AI 재계획 응답이 현재 재계획 snapshot보다 오래되었습니다."
    ),
    REOPTIMIZATION_PLAN_STAGE_DUPLICATE(
            HttpStatus.CONFLICT,
            "SIMULATION_RUN_029",
            "동일한 재계획 staging 계획이 이미 존재합니다."
    ),
    REOPTIMIZATION_PLAN_STAGE_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "SIMULATION_RUN_030",
            "검증된 재계획 계획을 staging 저장하지 못했습니다."
    ),
    REOPTIMIZATION_PLAN_STAGE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "SIMULATION_RUN_031",
            "재계획 staging 계획을 찾을 수 없습니다."
    ),
    REOPTIMIZATION_PLAN_APPLY_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "SIMULATION_RUN_032",
            "재계획 계획을 DB에 적용하지 못했습니다."
    ),
    REOPTIMIZATION_PLAN_TASK_STATE_CHANGED(
            HttpStatus.CONFLICT,
            "SIMULATION_RUN_033",
            "재계획 이후 작업 상태 또는 담당 로봇이 변경되었습니다."
    ),
    REOPTIMIZATION_PLAN_STAGE_INVALID_STATUS(
            HttpStatus.CONFLICT,
            "SIMULATION_RUN_034",
            "현재 상태의 재계획 staging 계획은 적용할 수 없습니다."
    ),
    REOPTIMIZATION_PLAN_ACTIVATION_NOT_IMPLEMENTED(
            HttpStatus.NOT_IMPLEMENTED,
            "SIMULATION_RUN_035",
            "DB 적용이 끝난 재계획 계획의 Runtime 활성화는 아직 구현되지 않았습니다."
    ),

    REOPTIMIZATION_RUNTIME_CONTEXT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "SIMULATION_RUN_036",
            "재계획 계획을 설치할 Runtime context를 찾을 수 없습니다."
    ),
    REOPTIMIZATION_RUNTIME_PLAN_INSTALL_FAILED(
            HttpStatus.CONFLICT,
            "SIMULATION_RUN_037",
            "재계획 계획을 Runtime에 원자적으로 설치하지 못했습니다."
    ),
    REOPTIMIZATION_RUNTIME_PLAN_ALREADY_INSTALLED(
            HttpStatus.CONFLICT,
            "SIMULATION_RUN_038",
            "다른 재계획 계획이 이미 Runtime에 설치되어 있습니다."
    ),
    REOPTIMIZATION_RUNTIME_STATE_INVALID(
            HttpStatus.CONFLICT,
            "SIMULATION_RUN_039",
            "Runtime context가 재계획 계획을 설치할 수 있는 상태가 아닙니다."
    ),
    REOPTIMIZATION_PLAN_RUNTIME_ACTIVATION_NOT_IMPLEMENTED(
            HttpStatus.NOT_IMPLEMENTED,
            "SIMULATION_RUN_040",
            "Runtime 실행 활성화와 전체 로봇 동시 재개는 아직 구현되지 않았습니다."
    ),

    STORAGE_LOCATION_NOT_FOUND(HttpStatus.NOT_FOUND, "STORAGE_LOCATION_001", "존재하지 않는 보관위치입니다."),

    WAREHOUSE_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "WAREHOUSE_ITEM_001", "존재하지 않는 창고 품목입니다."),
    WAREHOUSE_ITEM_LOCATION_MISMATCH(HttpStatus.BAD_REQUEST, "WAREHOUSE_ITEM_002", "보관위치가 지정한 창고에 속해 있지 않습니다."),

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_001", "입력값이 올바르지 않습니다."),

    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "USER_001", "이미 사용 중인 이메일입니다."),
    FULFILLMENT_COMMAND_NOT_GENERATED(
            HttpStatus.CONFLICT,
            "FULFILLMENT_COMMAND_001",
            "재고, 빈 보관 위치 또는 입출고 노드가 부족하여 명령을 생성할 수 없습니다."
    ),

    LARO_PLAN_NOT_EXECUTABLE(
            HttpStatus.CONFLICT,
            "LARO_PLAN_001",
            "AI 계획이 READY 상태가 아니거나 실행 단계가 없습니다."
    ),
    LARO_PLAN_MAPPING_FAILED(
            HttpStatus.BAD_REQUEST,
            "LARO_PLAN_002",
            "AI 계획의 로봇, 노드 또는 작업을 현재 시뮬레이션 데이터와 연결할 수 없습니다."
    ),

    EMAIL_VERIFICATION_NOT_FOUND(HttpStatus.BAD_REQUEST, "AUTH_006", "이메일 인증번호가 없거나 만료되었습니다."),
    EMAIL_VERIFICATION_MISMATCH(HttpStatus.BAD_REQUEST, "AUTH_007", "이메일 인증번호가 일치하지 않습니다."),
    EMAIL_VERIFICATION_REQUIRED(HttpStatus.BAD_REQUEST, "AUTH_008", "이메일 인증을 완료해주세요."),
    EMAIL_VERIFICATION_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "AUTH_009", "잠시 후 인증번호를 다시 요청해주세요."),
    EMAIL_SEND_FAILED(HttpStatus.BAD_GATEWAY, "AUTH_010", "인증 이메일을 발송하지 못했습니다."),
    EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "AUTH_011", "인증번호 확인 횟수를 초과했습니다. 인증번호를 다시 요청해주세요."),

    BOARD_POST_ATTACHMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "BOARD_POST_002", "첨부파일을 찾을 수 없습니다."),
    BOARD_POST_ATTACHMENT_TOO_LARGE(HttpStatus.BAD_REQUEST, "BOARD_POST_003", "첨부파일은 최대 2MB까지 업로드할 수 있습니다."),
    BOARD_POST_ATTACHMENT_EMPTY(HttpStatus.BAD_REQUEST, "BOARD_POST_004", "비어 있는 파일은 업로드할 수 없습니다."),

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_002", "사용자를 찾을 수 없습니다."),
    CURRENT_PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "USER_003", "현재 비밀번호가 일치하지 않습니다."),
    SAME_PASSWORD(HttpStatus.BAD_REQUEST, "USER_004", "현재 비밀번호와 다른 비밀번호를 입력해주세요."),
    SHARED_WAREHOUSE_OWNER_WITHDRAWAL_NOT_ALLOWED(
            HttpStatus.CONFLICT,
            "USER_005",
            "공용 창고를 소유한 관리 계정은 탈퇴할 수 없습니다."
    ),

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
