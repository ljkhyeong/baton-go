package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.FileSystemResource;

@Tag("mysql")
class MySqlImageContractTest {

    @Test
    @DisplayName("Testcontainers와 Compose는 같은 MySQL 이미지를 사용한다")
    @SuppressWarnings("unchecked")
    void keepsMysqlImageDigestAligned() {
        String testImage = MySqlTestImage.NAME.asCanonicalNameString();
        Map<String, Object> compose = yaml("compose.yml");
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> mysqlService = (Map<String, Object>) services.get("mysql");
        assertThat(mysqlService.get("image")).isEqualTo(testImage);
    }

    private Map<String, Object> yaml(String relativePath) {
        Path repositoryRoot = Path.of(System.getProperty("batonGo.repositoryRoot"));
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(new FileSystemResource(repositoryRoot.resolve(relativePath)));
        return factory.getObject();
    }
}
