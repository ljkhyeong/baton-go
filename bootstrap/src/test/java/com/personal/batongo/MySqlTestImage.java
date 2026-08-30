package com.personal.batongo;

import org.testcontainers.utility.DockerImageName;

public final class MySqlTestImage {

    public static final DockerImageName NAME = DockerImageName.parse(
            "mysql:8.4.11@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb"
    ).asCompatibleSubstituteFor("mysql");

    private MySqlTestImage() {
    }
}
