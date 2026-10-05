package com.personal.batongo;

import com.personal.batongo.application.link.LinkCodeKeyGuard;
import com.personal.batongo.bootstrap.DatabaseMigrationRunner;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BatonGoApplication {

    public static void main(String[] args) {
        if (DatabaseMigrationRunner.isRequested(args)) {
            DatabaseMigrationRunner.run(args);
            return;
        }
        SpringApplication.run(BatonGoApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    @Bean
    ApplicationRunner linkCodeKeyStartupValidator(LinkCodeKeyGuard linkCodeKeyGuard) {
        return arguments -> linkCodeKeyGuard.verifyOrBind();
    }
}
