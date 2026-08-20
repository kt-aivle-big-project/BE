package com.aivle.be.simulationrun.playback;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SimulationPlaybackScheduler {

    private static final Logger log = LoggerFactory.getLogger(SimulationPlaybackScheduler.class);

    private final SimulationPlaybackService playbackService;

    @Value("${simulation.playback.tick-ms:100}")
    private long tickMs;

    @Scheduled(fixedRateString = "${simulation.playback.tick-ms:100}")
    public void tick() {
        try {
            playbackService.tick(tickMs);
        } catch (Exception exception) {
            log.error("[재생] 틱 처리 중 오류", exception);
        }
    }
}
