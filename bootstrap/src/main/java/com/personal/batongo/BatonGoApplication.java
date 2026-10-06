package com.personal.batongo;

import com.personal.batongo.application.link.port.out.LinkCodeKeyGuardPort;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongomigration.DatabaseMigrationConfiguration;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BatonGoApplication {

    public static void main(String[] args) {
        if (DatabaseMigrationConfiguration.isRequested(args)) {
            DatabaseMigrationConfiguration.run(args);
            return;
        }
        SpringApplication.run(BatonGoApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    @Bean
    ApplicationRunner linkCodeKeyStartupValidator(
            TransactionTemplate transactions, LinkCodeKeyGuardPort keyGuard, LinkCodePort linkCodes
    ) {
        return arguments -> transactions.executeWithoutResult(
                status -> keyGuard.verifyOrBind(linkCodes.keyRingIdentity())
        );
    }
}
