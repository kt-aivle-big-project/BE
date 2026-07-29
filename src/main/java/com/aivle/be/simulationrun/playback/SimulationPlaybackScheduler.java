package com.aivle.be.simulationrun.playback;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 재생 엔진을 주기적으로 구동한다.
 * 틱 주기만큼 시뮬레이션 시계를 전진시킨다.
 */
@Component
@RequiredArgsConstructor
public class SimulationPlaybackScheduler {

    private static final Logger log = LoggerFactory.getLogger(SimulationPlaybackScheduler.class);

    private final SimulationPlaybackService playbackService;

    @Value("${simulation.playback.tick-ms:500}")
    private long tickMs;

    @Scheduled(fixedRateString = "${simulation.playback.tick-ms:500}")
    public void tick() {
        try {
            playbackService.tick(tickMs);
        } catch (Exception exception) {
            log.error("[재생] 틱 처리 중 오류", exception);
        }
    }
}
