package com.personal.batongo;

import org.testcontainers.utility.DockerImageName;

public final class MySqlTestImage {

    public static final DockerImageName NAME = DockerImageName.parse(
            "mysql:8.4.10@sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6"
    ).asCompatibleSubstituteFor("mysql");

    private MySqlTestImage() {
    }
}
