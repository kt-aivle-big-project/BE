package com.aivle.be.simulationrun.commandcycle;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SimulationCommandCycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(SimulationCommandCycleScheduler.class);

    private final SimulationCommandCycleService cycleService;

    @Scheduled(fixedRateString = "${simulation.command-cycle.tick-ms:500}")
    public void tick() {
        try {
            cycleService.tick();
        } catch (RuntimeException exception) {
            log.error("[command-cycle] tick failed", exception);
        }
    }
}
