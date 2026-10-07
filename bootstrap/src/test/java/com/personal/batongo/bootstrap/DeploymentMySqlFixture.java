package com.personal.batongo.bootstrap;

import com.github.dockerjava.api.model.Capability;
import com.personal.batongo.MySqlTestImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import org.springframework.boot.ssl.pem.PemContent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

final class DeploymentMySqlFixture extends GenericContainer<DeploymentMySqlFixture> {

    private static final int MYSQL_PORT = 3306;
    private static final String VERIFIED_HOST = "baton-go-mysql";
    private static final String CA_CERTIFICATE_PATH = "/etc/mysql/tls/ca.pem";
    private static final String DATABASE = "baton_go";
    private static final String MIGRATION_USERNAME = "baton_go_migrator";
    private static final String MIGRATION_PASSWORD =
            "migration_test_password_0123456789abcdef";
    private static final String RUNTIME_USERNAME = "baton_go";
    private static final String RUNTIME_PASSWORD =
            "runtime_test_password_0123456789abcdef";
    private static final String ROOT_PASSWORD =
            "root_test_password_0123456789abcdef";
    private static final String TRUSTSTORE_PASSWORD = "baton-go-public-ca-v1";
    private static final String TLS_ENTRYPOINT_RESOURCE =
            "com/personal/batongo/bootstrap/mysql/generate-test-tls-and-start.sh";
    private static final String RUNTIME_INIT_SCRIPT =
            "deploy/k8s/base/mysql-runtime-user-init.sh";

    DeploymentMySqlFixture() {
        super(MySqlTestImage.NAME);

        withEnv("MYSQL_DATABASE", DATABASE);
        withEnv("MYSQL_USER", MIGRATION_USERNAME);
        withEnv("MYSQL_PASSWORD", MIGRATION_PASSWORD);
        withEnv("MYSQL_ROOT_PASSWORD", ROOT_PASSWORD);
        withEnv("MYSQL_ROOT_HOST", "localhost");
        withEnv("BATON_GO_DB_USERNAME", RUNTIME_USERNAME);
        withEnv("BATON_GO_DB_PASSWORD", RUNTIME_PASSWORD);
        withEnv("TZ", "UTC");
        withTmpFs(Map.of(
                "/etc/mysql/tls", "rw,uid=999,gid=999,mode=0750",
                "/var/lib/mysql", "rw,uid=999,gid=999,mode=0770"
        ));
        withExposedPorts(MYSQL_PORT);
        withCopyFileToContainer(
                MountableFile.forHostPath(MySqlTestImage.repositoryFile(RUNTIME_INIT_SCRIPT), 0555),
                "/docker-entrypoint-initdb.d/10-create-runtime-user.sh"
        );
        withCopyFileToContainer(
                MountableFile.forClasspathResource(TLS_ENTRYPOINT_RESOURCE, 0755),
                "/baton-go-test-entrypoint.sh"
        );
        withCreateContainerCmdModifier(command -> {
            command.withEntrypoint("/baton-go-test-entrypoint.sh");
            command.withUser("999:999");
            command.getHostConfig().withCapDrop(Capability.ALL);
        });
        withCommand(
                "--collation-server=utf8mb4_unicode_ci",
                "--default-time-zone=+00:00",
                "--require-secure-transport=ON",
                "--ssl-ca=/etc/mysql/tls/ca.pem",
                "--ssl-cert=/etc/mysql/tls/tls.crt",
                "--ssl-key=/etc/mysql/tls/tls.key"
        );
        waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3)));
    }

    String[] migrationArguments(String jdbcUrl) {
        return new String[]{
                "--baton-go.migration-only=true",
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + MIGRATION_USERNAME,
                "--spring.datasource.password=" + MIGRATION_PASSWORD,
                "--logging.level.root=OFF"
        };
    }

    Path createTruststore(Path truststorePath)
            throws Exception {
        var certificateRead = execInContainer("cat", CA_CERTIFICATE_PATH);
        if (certificateRead.getExitCode() != 0) {
            throw new IllegalStateException("배포 MySQL 테스트 CA를 읽지 못했습니다");
        }
        var certificate = PemContent.of(certificateRead.getStdout()).getCertificates().getFirst();

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        char[] password = TRUSTSTORE_PASSWORD.toCharArray();
        keyStore.load(null, password);
        keyStore.setCertificateEntry("baton-go-test-ca", certificate);
        try (var outputStream = Files.newOutputStream(truststorePath)) {
            keyStore.store(outputStream, password);
        }
        return truststorePath;
    }

    String verifiedJdbcUrl(Path truststore) {
        return "jdbc:mysql://" + VERIFIED_HOST + ":" + getMappedPort(MYSQL_PORT)
                + "/" + DATABASE
                + "?sslMode=VERIFY_IDENTITY"
                + "&trustCertificateKeyStoreUrl=" + truststore.toUri()
                + "&trustCertificateKeyStoreType=PKCS12"
                + "&fallbackToSystemTrustStore=false"
                + "&serverTimezone=UTC";
    }

    /** 실행 계정으로 연결마다 새로 접속하는 JdbcClient를 만든다. 비밀번호는 URL에 넣지 않는다. */
    JdbcClient runtimeJdbcClient(String jdbcUrl) {
        Properties properties = new Properties();
        properties.setProperty("user", RUNTIME_USERNAME);
        properties.setProperty("password", RUNTIME_PASSWORD);
        properties.setProperty("connectTimeout", "3000");
        properties.setProperty("socketTimeout", "30000");
        properties.setProperty("trustCertificateKeyStorePassword", TRUSTSTORE_PASSWORD);
        return JdbcClient.create(new DriverManagerDataSource(jdbcUrl, properties));
    }
}
