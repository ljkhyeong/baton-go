package com.personal.batongo.bootstrap;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
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
        super(DockerImageName.parse("mysql:8.4.10"));

        withEnv("MYSQL_DATABASE", DATABASE);
        withEnv("MYSQL_USER", MIGRATION_USERNAME);
        withEnv("MYSQL_PASSWORD", MIGRATION_PASSWORD);
        withEnv("MYSQL_ROOT_PASSWORD", ROOT_PASSWORD);
        withEnv("MYSQL_ROOT_HOST", "localhost");
        withEnv("BATON_GO_DB_USERNAME", RUNTIME_USERNAME);
        withEnv("BATON_GO_DB_PASSWORD", RUNTIME_PASSWORD);
        withEnv("TZ", "UTC");
        withExposedPorts(MYSQL_PORT);
        withCopyFileToContainer(
                MountableFile.forHostPath(repositoryFile(RUNTIME_INIT_SCRIPT), 0555),
                "/docker-entrypoint-initdb.d/10-create-runtime-user.sh"
        );
        withCopyFileToContainer(
                MountableFile.forClasspathResource(TLS_ENTRYPOINT_RESOURCE, 0755),
                "/baton-go-test-entrypoint.sh"
        );
        withCreateContainerCmdModifier(command ->
                command.withEntrypoint("/baton-go-test-entrypoint.sh")
        );
        withCommand(
                "--character-set-server=utf8mb4",
                "--collation-server=utf8mb4_unicode_ci",
                "--default-time-zone=+00:00",
                "--require-secure-transport=ON",
                "--ssl-ca=/etc/mysql/tls/ca.pem",
                "--ssl-cert=/etc/mysql/tls/tls.crt",
                "--ssl-key=/etc/mysql/tls/tls.key"
        );
        waitingFor(Wait.forSuccessfulCommand("""
                MYSQL_PWD="${BATON_GO_DB_PASSWORD}" \
                mysql --protocol=TCP \
                  --host=127.0.0.1 \
                  --ssl-mode=REQUIRED \
                  --user="${BATON_GO_DB_USERNAME}" \
                  --database="${MYSQL_DATABASE}" \
                  --execute='SELECT 1' >/dev/null 2>&1
                """).withStartupTimeout(Duration.ofMinutes(3)));
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
        byte[] certificatePem = copyFileFromContainer(
                CA_CERTIFICATE_PATH,
                inputStream -> inputStream.readAllBytes()
        );
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        Certificate certificate;
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(certificatePem)) {
            certificate = certificateFactory.generateCertificate(inputStream);
        }

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

    Connection connectAsMigrator(String jdbcUrl) throws SQLException {
        return connect(jdbcUrl, MIGRATION_USERNAME, MIGRATION_PASSWORD);
    }

    Connection connectAsRuntime(String jdbcUrl) throws SQLException {
        return connect(jdbcUrl, RUNTIME_USERNAME, RUNTIME_PASSWORD);
    }

    private static Path repositoryFile(String relativePath) {
        String repositoryRoot = System.getProperty("batonGo.repositoryRoot");
        if (repositoryRoot == null || repositoryRoot.isBlank()) {
            throw new IllegalStateException("BATON GO repository root test 설정이 없습니다");
        }
        return Path.of(repositoryRoot).resolve(relativePath);
    }

    private static Connection connect(String jdbcUrl, String username, String password)
            throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty(
                "trustCertificateKeyStorePassword",
                TRUSTSTORE_PASSWORD
        );
        return DriverManager.getConnection(jdbcUrl, properties);
    }
}
