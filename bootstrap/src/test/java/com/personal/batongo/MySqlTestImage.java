package com.personal.batongo;

import java.nio.file.Path;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class MySqlTestImage {

    /** 로컬 Compose와 같은 MySQL 이미지를 쓰도록 compose.yml의 값을 그대로 읽는다. */
    public static final DockerImageName NAME =
            DockerImageName.parse(composeImage()).asCompatibleSubstituteFor("mysql");

    private MySqlTestImage() {
    }

    /** 테스트 클래스마다 데이터가 섞이지 않도록 새 컨테이너를 만든다. */
    public static MySQLContainer container() {
        return new MySQLContainer(NAME)
                .withUrlParam("connectTimeout", "3000")
                .withUrlParam("socketTimeout", "30000");
    }

    /** mysqlTest 작업이 넘긴 저장소 루트 기준 경로를 만든다. */
    public static Path repositoryFile(String relativePath) {
        String repositoryRoot = System.getProperty("batonGo.repositoryRoot");
        if (repositoryRoot == null || repositoryRoot.isBlank()) {
            throw new IllegalStateException("BATON GO repository root test 설정이 없습니다");
        }
        return Path.of(repositoryRoot).resolve(relativePath);
    }

    private static String composeImage() {
        var compose = new YamlPropertiesFactoryBean();
        compose.setResources(new FileSystemResource(repositoryFile("compose.yml")));
        String image = compose.getObject().getProperty("services.mysql.image");
        if (image == null) {
            throw new IllegalStateException("compose.yml에 MySQL 이미지가 없습니다");
        }
        return image;
    }
}
