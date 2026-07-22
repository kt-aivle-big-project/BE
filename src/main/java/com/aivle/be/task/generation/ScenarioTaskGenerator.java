package com.aivle.be.task.generation;

import com.aivle.be.simulationrun.domain.ScenarioType;
import com.aivle.be.task.service.TaskCreateCommand;

import java.util.List;

public interface ScenarioTaskGenerator {

    boolean supports(ScenarioType scenarioType);

    List<TaskCreateCommand> generate(ScenarioGenerationContext context);
}
