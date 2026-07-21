package com.aivle.be.robot.service;

import com.aivle.be.robot.dto.RobotCreateRequest;
import com.aivle.be.robot.dto.RobotResponse;
import com.aivle.be.robot.dto.RobotUpdateRequest;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotspec.entity.RobotSpec;
import com.aivle.be.robotspec.repository.RobotSpecRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RobotService {

    private final RobotRepository robotRepository;
    private final RobotSpecRepository robotSpecRepository;
    private final WarehouseRepository warehouseRepository;

    @Transactional
    public RobotResponse createRobot(RobotCreateRequest request) {
        RobotSpec robotSpec = robotSpecRepository
                .findById(request.getRobotSpecId())
                .orElseThrow(() ->
                        new IllegalArgumentException("로봇 사양을 찾을 수 없습니다.")
                );

        Warehouse warehouse = warehouseRepository
                .findById(request.getWarehouseId())
                .orElseThrow(() ->
                        new IllegalArgumentException("창고를 찾을 수 없습니다.")
                );

        Robot robot = Robot.create(
                robotSpec,
                warehouse,
                request.getNodeId(),
                request.getBattery(),
                request.getStatus()
        );

        return RobotResponse.from(robotRepository.save(robot));
    }

    public RobotResponse getRobot(Long robotId) {
        Robot robot = findRobot(robotId);
        return RobotResponse.from(robot);
    }

    public List<RobotResponse> getRobots() {
        return robotRepository.findAll()
                .stream()
                .map(RobotResponse::from)
                .toList();
    }

    public List<RobotResponse> getRobotsByWarehouse(Long warehouseId) {
        if (!warehouseRepository.existsById(warehouseId)) {
            throw new IllegalArgumentException("창고를 찾을 수 없습니다.");
        }

        return robotRepository.findAllByWarehouse_Id(warehouseId)
                .stream()
                .map(RobotResponse::from)
                .toList();
    }

    @Transactional
    public RobotResponse updateRobot(
            Long robotId,
            RobotUpdateRequest request
    ) {
        Robot robot = findRobot(robotId);

        RobotSpec robotSpec = robotSpecRepository
                .findById(request.getRobotSpecId())
                .orElseThrow(() ->
                        new IllegalArgumentException("로봇 사양을 찾을 수 없습니다.")
                );

        Warehouse warehouse = warehouseRepository
                .findById(request.getWarehouseId())
                .orElseThrow(() ->
                        new IllegalArgumentException("창고를 찾을 수 없습니다.")
                );

        robot.update(
                robotSpec,
                warehouse,
                request.getNodeId(),
                request.getBattery(),
                request.getStatus()
        );

        return RobotResponse.from(robot);
    }

    @Transactional
    public void deleteRobot(Long robotId) {
        Robot robot = findRobot(robotId);
        robotRepository.delete(robot);
    }

    private Robot findRobot(Long robotId) {
        return robotRepository.findById(robotId)
                .orElseThrow(() ->
                        new IllegalArgumentException("로봇을 찾을 수 없습니다.")
                );
    }
}