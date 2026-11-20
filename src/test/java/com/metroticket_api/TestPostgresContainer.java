package com.metroticket_api;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public final class TestPostgresContainer {

    private TestPostgresContainer() {
    }

    public static PostgreSQLContainer create() {
        DockerImageName image = DockerImageName.parse("pgvector/pg17").asCompatibleSubstituteFor("postgres");
        return new PostgreSQLContainer(image);
    }
}
