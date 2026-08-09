package com.personal.batongo;

import com.personal.batongo.bootstrap.DatabaseMigrationRunner;
import java.time.Clock;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BatonGoApplication {

    private static final String GUARD_BINDING_CONFIRMATION =
            "--confirm-writers-stopped";

    public static void main(String[] args) {
        requireNormalApplicationArguments(args);
        if (DatabaseMigrationRunner.isRequested(args)) {
            DatabaseMigrationRunner.run(args);
            return;
        }
        SpringApplication.run(BatonGoApplication.class, args);
    }

    static void requireNormalApplicationArguments(String[] args) {
        if (Arrays.asList(args).contains(GUARD_BINDING_CONFIRMATION)) {
            throw new IllegalArgumentException(
                    "guard 결합 확인 인자는 전용 guard binding 도구에서만 사용할 수 있습니다"
            );
        }
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
