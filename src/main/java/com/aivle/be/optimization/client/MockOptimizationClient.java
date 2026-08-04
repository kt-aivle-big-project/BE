package com.aivle.be.optimization.client;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.dto.request.OptimizationRequest;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.response.OptimizationResponse;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import com.aivle.be.optimization.validation.ReoptimizationPlanContractValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * fixture에 명시된 계획만 반환하는 개발용 AI client.
 * 경로 계산이나 BFS fallback은 수행하지 않는다.
 */
@Component
@Profile("mock-ai")
public class MockOptimizationClient implements OptimizationClient {

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final String reoptimizationFixtureLocation;

    public MockOptimizationClient(
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            @Value("${mock-ai.reoptimization-fixture:}")
            String reoptimizationFixtureLocation
    ) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.reoptimizationFixtureLocation =
                reoptimizationFixtureLocation;
    }

    @Override
    public OptimizationResponse optimize(OptimizationRequest request) {
        throw new BusinessException(
                ErrorCode.MOCK_AI_PLAN_NOT_CONFIGURED
        );
    }

    @Override
    public ReoptimizationResponse reoptimize(
            ReoptimizationOptimizationRequest request
    ) {
        ReoptimizationResponse fixture = loadFixture();
        ReoptimizationResponse response = new ReoptimizationResponse(
                fixture.requestId(),
                request.replanId(),
                request.simulationRunId(),
                request.snapshotVersion(),
                fixture.status(),
                fixture.taskPlans(),
                fixture.unassignedTaskIds(),
                fixture.message()
        );

        try {
            validateFixtureReferences(request, response);
            ReoptimizationPlanContractValidator.validate(
                    request,
                    response
            );
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.MOCK_AI_PLAN_INVALID,
                    exception
            );
        }

        // 상관키는 현재 요청을 그대로 echo하고 계획 내용은 fixture만 사용한다.
        return response;
    }

    private void validateFixtureReferences(
            ReoptimizationOptimizationRequest request,
            ReoptimizationResponse response
    ) {
        if (response.status()
                != ReoptimizationResponse.Status.SUCCEEDED) {
            return;
        }

        Set<Long> taskIds = request.remainingTasks().stream()
                .map(ReoptimizationOptimizationRequest.TaskInput::taskId)
                .collect(Collectors.toSet());
        Set<Long> availableRobotIds = request.robots().stream()
                .filter(robot -> !"ERROR".equals(robot.status()))
                .filter(robot -> !"OFFLINE".equals(robot.status()))
                .map(ReoptimizationOptimizationRequest.RobotStateInput::robotId)
                .collect(Collectors.toSet());
        boolean invalidReference = response.taskPlans().stream()
                .anyMatch(plan ->
                        !taskIds.contains(plan.taskId())
                                || !availableRobotIds.contains(plan.robotId())
                );
        long distinctTaskCount = response.taskPlans().stream()
                .map(plan -> plan.taskId())
                .distinct()
                .count();

        if (invalidReference
                || distinctTaskCount != response.taskPlans().size()) {
            throw new IllegalArgumentException(
                    "Mock plan references an invalid task or robot"
            );
        }
    }

    private ReoptimizationResponse loadFixture() {
        if (reoptimizationFixtureLocation == null
                || reoptimizationFixtureLocation.isBlank()) {
            throw new BusinessException(
                    ErrorCode.MOCK_AI_PLAN_NOT_CONFIGURED
            );
        }

        Resource resource = resourceLoader.getResource(
                reoptimizationFixtureLocation
        );

        if (!resource.exists()) {
            throw new BusinessException(
                    ErrorCode.MOCK_AI_PLAN_NOT_CONFIGURED
            );
        }

        try (var inputStream = resource.getInputStream()) {
            return objectMapper.readValue(
                    inputStream,
                    ReoptimizationResponse.class
            );
        } catch (Exception exception) {
            throw new BusinessException(
                    ErrorCode.MOCK_AI_PLAN_INVALID,
                    exception
            );
        }
    }

}
