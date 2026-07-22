package com.aivle.be.robotspec.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotspec.dto.RobotSpecRequest;
import com.aivle.be.robotspec.dto.RobotSpecResponse;
import com.aivle.be.robotspec.entity.RobotSpec;
import com.aivle.be.robotspec.repository.RobotSpecRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RobotSpecService {

    private final RobotSpecRepository robotSpecRepository;
    private final RobotRepository robotRepository;

    @Transactional
    public RobotSpecResponse create(RobotSpecRequest request) {
        String robotCode = request.robotCode().trim();
        if (robotSpecRepository.existsByRobotCode(robotCode)) {
            throw new BusinessException(ErrorCode.DUPLICATE_ROBOT_CODE);
        }
        RobotSpec spec = RobotSpec.create(
                robotCode,
                request.taskCode().trim(),
                request.baseBatteryRate(),
                request.workBatteryRate(),
                request.failureRate()
        );
        return RobotSpecResponse.from(robotSpecRepository.save(spec));
    }

    @Transactional(readOnly = true)
    public RobotSpecResponse get(Long robotSpecId) {
        return RobotSpecResponse.from(findById(robotSpecId));
    }

    @Transactional(readOnly = true)
    public List<RobotSpecResponse> getAll() {
        return robotSpecRepository.findAll().stream()
                .map(RobotSpecResponse::from)
                .toList();
    }

    @Transactional
    public RobotSpecResponse update(Long robotSpecId, RobotSpecRequest request) {
        RobotSpec spec = findById(robotSpecId);
        requireNotInUse(robotSpecId);
        String robotCode = request.robotCode().trim();
        if (robotSpecRepository.existsByRobotCodeAndIdNot(
                robotCode,
                robotSpecId
        )) {
            throw new BusinessException(ErrorCode.DUPLICATE_ROBOT_CODE);
        }
        spec.update(
                robotCode,
                request.taskCode().trim(),
                request.baseBatteryRate(),
                request.workBatteryRate(),
                request.failureRate()
        );
        return RobotSpecResponse.from(spec);
    }

    @Transactional
    public void delete(Long robotSpecId) {
        RobotSpec spec = findById(robotSpecId);
        requireNotInUse(robotSpecId);
        robotSpecRepository.delete(spec);
    }

    private RobotSpec findById(Long robotSpecId) {
        return robotSpecRepository.findById(robotSpecId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROBOT_SPEC_NOT_FOUND));
    }

    private void requireNotInUse(Long robotSpecId) {
        if (robotRepository.existsByRobotSpec_Id(robotSpecId)) {
            throw new BusinessException(ErrorCode.ROBOT_SPEC_IN_USE);
        }
    }
}
