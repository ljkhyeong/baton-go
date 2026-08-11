package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.batongo.application.link.SmartLinkService;
import com.personal.batongomigration.DatabaseMigrationConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DatabaseMigrationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(DatabaseMigrationConfiguration.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:mysql://localhost:3307/baton_go",
                    "spring.datasource.username=baton_go_migrator",
                    "spring.datasource.password=migration-password",
                    "spring.flyway.enabled=false"
            );

    @Test
    @DisplayName("migration context는 DataSource만 조립하고 일반 애플리케이션 component를 스캔하지 않는다")
    void createsOnlyMigrationInfrastructure() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DataSource.class);
            assertThat(context).doesNotHaveBean(SmartLinkService.class);
            assertThat(context).doesNotHaveBean(LinkCodeKeyStartupValidator.class);
            assertThat(context.getBean(DataSource.class))
                    .isInstanceOfSatisfying(HikariDataSource.class, dataSource -> {
                        assertThat(dataSource.getDataSourceProperties())
                                .containsEntry(
                                        "trustCertificateKeyStorePassword",
                                        "baton-go-public-ca-v1"
                                );
                        assertThat(dataSource.getJdbcUrl())
                                .doesNotContain("trustCertificateKeyStorePassword");
                    });
        });
    }

    @Test
    @DisplayName("migration 구성은 일반 애플리케이션 component scan 패키지 밖에 둔다")
    void keepsMigrationConfigurationOutsideNormalComponentScan() {
        assertThat(DatabaseMigrationConfiguration.class.getPackageName())
                .doesNotStartWith("com.personal.batongo.");
    }

}
