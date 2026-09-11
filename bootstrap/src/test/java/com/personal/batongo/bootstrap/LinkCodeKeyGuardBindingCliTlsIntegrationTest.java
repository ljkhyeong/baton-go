package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.batongo.BatonGoApplication;
import com.personal.batongo.adapter.out.external.link.LinkCodeProperties;
import com.personal.batongo.adapter.out.external.link.SecureLinkCodeAdapter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("mysql")
@Testcontainers
class LinkCodeKeyGuardBindingCliTlsIntegrationTest {

    @Container
    static final DeploymentMySqlFixture MYSQL = new DeploymentMySqlFixture();

    @Test
    @DisplayName("키 등록 CLI는 운영 TLS 설정으로 기존 데이터베이스에 키 정보를 등록한다")
    void bindsExistingDatabaseThroughVerifiedTls(@TempDir Path directory) throws Exception {
        String jdbcUrl = MYSQL.verifiedJdbcUrl(MYSQL.createTruststore(
                directory.resolve("trusted-ca.p12")
        ));
        BatonGoApplication.main(MYSQL.migrationArguments(jdbcUrl));

        String secret = "guard-cli-tls-test-secret-0123456789abcdef";
        String canary = "60fa8eb4-e104-459d-aa45-e4a07831998e";
        String linkId = "2df34d5c-2d80-4ae3-a82c-16ae447b3a61";
        var linkCodePort = new SecureLinkCodeAdapter(new LinkCodeProperties(secret));
        try (Connection connection = MYSQL.connectAsRuntime(jdbcUrl)) {
            JdbcClient jdbcClient = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            jdbcClient.sql("""
                            INSERT INTO smart_links (
                                id, code_hash, target_system, target_path, purpose, created_at
                            ) VALUES (
                                UUID_TO_BIN(?), ?, 'ROUND', '/room/abcd-efgh-jkmn',
                                'MEETING_ENTRY', UTC_TIMESTAMP(6)
                            )
                            """)
                    .params(linkId, linkCodePort.issue(canary).codeHash())
                    .update();
            jdbcClient.sql("""
                            INSERT INTO link_creation_requests (idempotency_key_hash, link_id, created_at)
                            VALUES (?, UUID_TO_BIN(?), UTC_TIMESTAMP(6))
                            """)
                    .params(linkCodePort.hashIdempotencyKey(canary), linkId)
                    .update();

            Path guardJar = Path.of(System.getProperty("batonGo.repositoryRoot"),
                    "guard-tool/build/libs/baton-go-guard-binding.jar");
            ProcessBuilder processBuilder = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-Djdk.net.hosts.file=" + System.getProperty("jdk.net.hosts.file"),
                    "-jar", guardJar.toString(), "--confirm-writers-stopped"
            ).redirectErrorStream(true);
            var environment = processBuilder.environment();
            environment.put("BATON_GO_DB_URL", jdbcUrl);
            environment.put("BATON_GO_DB_USERNAME", MYSQL.getEnvMap().get("BATON_GO_DB_USERNAME"));
            environment.put("BATON_GO_DB_PASSWORD", MYSQL.getEnvMap().get("BATON_GO_DB_PASSWORD"));
            environment.put("BATON_GO_LINK_CODE_SECRET", secret);

            Process process = processBuilder.start();
            try {
                try (var input = process.outputWriter(StandardCharsets.UTF_8)) {
                    input.write(canary);
                    input.newLine();
                }
                assertThat(process.waitFor(30, TimeUnit.SECONDS))
                        .as("키 등록 CLI가 제한 시간 안에 종료되어야 한다")
                        .isTrue();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(process.exitValue()).as("키 등록 CLI 출력: %s", output).isZero();
            } finally {
                process.destroyForcibly();
            }

            var identity = linkCodePort.derivationIdentity();
            assertThat(jdbcClient.sql("""
                            SELECT COUNT(*) FROM link_code_key_guard
                            WHERE guard_id = 1 AND derivation_version = ? AND key_fingerprint = ?
                            """)
                    .params(identity.version(), identity.hmacFingerprint())
                    .query(Integer.class)
                    .single()).isEqualTo(1);
        }
    }
}
