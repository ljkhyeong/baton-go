package com.personal.batongo.bootstrap;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/** 의존성을 준비 상태에 넣지 않으므로 트래픽이 없어도 DB·Redis 장애를 경보하도록 시작 상태 묶음을 지표로 낸다. */
@Component
public class StartupHealthMetrics implements MeterBinder {

    private final HealthEndpoint healthEndpoint;

    public StartupHealthMetrics(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        // Spring Boot 문서의 상태 확인 지표 연결 방식대로 수집할 때마다 시작 상태 묶음을 평가한다.
        Gauge.builder("baton.go.startup.health.up", healthEndpoint, StartupHealthMetrics::up)
                .strongReference(true)
                .register(registry);
    }

    private static double up(HealthEndpoint endpoint) {
        HealthDescriptor startup = endpoint.healthForPath("startup");
        return startup != null && Status.UP.equals(startup.getStatus()) ? 1 : 0;
    }
}
