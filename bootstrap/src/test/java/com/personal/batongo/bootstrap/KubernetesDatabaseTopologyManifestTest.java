package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class KubernetesDatabaseTopologyManifestTest {

    private static final Path REPOSITORY_ROOT = repositoryRoot();
    private static final String MYSQL_BASE_IMAGE = "mysql:8.4";
    private static final String MYSQL_PRIVATE_DIGEST =
            "sha256:8dbcf531a03aade657e181b9cf2f1d1803ce621a1d55610cb44cb531ab7d7db6";

    @Test
    @DisplayName("MySQL preflight는 데이터 PVC 없이 고정 이미지와 최소 권한으로 TLS 입력을 검증한다")
    void keepsMySqlPreflightAheadOfDataInitialization() {
        Map<String, Object> statefulSet = loadYaml(
                "deploy/k8s/base/mysql-statefulset.yaml"
        );
        Map<String, Object> podSpec = nested(
                statefulSet,
                "spec",
                "template",
                "spec"
        );
        Map<String, Object> preflight = named(
                objectList(podSpec.get("initContainers")),
                "mysql-bootstrap-preflight"
        );
        Map<String, Object> mysql = named(
                objectList(podSpec.get("containers")),
                "mysql"
        );

        assertThat(preflight.get("image")).isEqualTo(MYSQL_BASE_IMAGE);
        assertThat(mysql.get("image")).isEqualTo(MYSQL_BASE_IMAGE);
        assertThat(preflight.get("command")).isEqualTo(List.of(
                "/bin/sh",
                "/opt/baton-go/mysql-preflight/mysql-init-preflight.sh"
        ));
        assertThat(preflight.get("args")).isEqualTo(List.of(
                "/etc/mysql/tls",
                "/opt/baton-go/mysql-init/10-create-runtime-user.sh"
        ));
        assertThat(mountNames(preflight))
                .containsExactlyInAnyOrder(
                        "mysql-init-preflight",
                        "runtime-user-init",
                        "mysql-server-tls"
                )
                .doesNotContain("data");
        assertThat(mountNames(mysql)).contains("data", "runtime-user-init", "mysql-server-tls");

        Map<String, Object> securityContext = nested(preflight, "securityContext");
        assertThat(securityContext)
                .containsEntry("runAsNonRoot", true)
                .containsEntry("runAsUser", 999)
                .containsEntry("runAsGroup", 999)
                .containsEntry("readOnlyRootFilesystem", true)
                .containsEntry("allowPrivilegeEscalation", false)
                .containsEntry("privileged", false);
        assertThat(stringList(nested(securityContext, "capabilities").get("drop")))
                .containsExactly("ALL");

        assertThat(secretName(environment(preflight, "MYSQL_PASSWORD")))
                .isEqualTo("baton-go-database-migration-credentials");
        assertThat(secretKey(environment(preflight, "MYSQL_PASSWORD")))
                .isEqualTo("BATON_GO_DB_MIGRATION_PASSWORD");
        assertThat(secretName(environment(preflight, "BATON_GO_DB_PASSWORD")))
                .isEqualTo("baton-go-database-runtime-credentials");
        assertThat(secretKey(environment(preflight, "BATON_GO_DB_PASSWORD")))
                .isEqualTo("BATON_GO_DB_PASSWORD");
        assertThat(secretName(environment(preflight, "MYSQL_ROOT_PASSWORD")))
                .isEqualTo("baton-go-database-bootstrap-credentials");
        assertThat(secretKey(environment(preflight, "MYSQL_ROOT_PASSWORD")))
                .isEqualTo("BATON_GO_DB_ROOT_PASSWORD");
        assertThat(configMapName(environment(preflight, "MYSQL_USER")))
                .isEqualTo("baton-go-database-identity");
        assertThat(configMapKey(environment(preflight, "MYSQL_USER")))
                .isEqualTo("BATON_GO_DB_MIGRATION_USERNAME");
        assertThat(configMapName(environment(preflight, "BATON_GO_DB_USERNAME")))
                .isEqualTo("baton-go-database-identity");
        assertThat(configMapKey(environment(preflight, "BATON_GO_DB_USERNAME")))
                .isEqualTo("BATON_GO_DB_USERNAME");

        assertSameDatabaseEnvironment(preflight, mysql);

        Map<String, Object> preflightVolume = named(
                objectList(podSpec.get("volumes")),
                "mysql-init-preflight"
        );
        Map<String, Object> runtimeInitVolume = named(
                objectList(podSpec.get("volumes")),
                "runtime-user-init"
        );
        Map<String, Object> tlsVolume = named(
                objectList(podSpec.get("volumes")),
                "mysql-server-tls"
        );
        assertThat(nested(preflightVolume, "configMap"))
                .containsEntry("name", "baton-go-mysql-init-preflight")
                .containsEntry("defaultMode", 365);
        assertThat(nested(runtimeInitVolume, "configMap"))
                .containsEntry("name", "baton-go-mysql-runtime-user-init")
                .containsEntry("defaultMode", 365);
        assertThat(nested(tlsVolume, "secret"))
                .containsEntry("secretName", "baton-go-mysql-server-tls")
                .containsEntry("defaultMode", 288);

        assertThat(stringList(mysql.get("args")))
                .contains(
                        "--require-secure-transport=ON",
                        "--ssl-ca=/etc/mysql/tls/ca.pem",
                        "--ssl-cert=/etc/mysql/tls/tls.crt",
                        "--ssl-key=/etc/mysql/tls/tls.key"
                );
        assertTlsProbe(mysql, "startupProbe");
        assertTlsProbe(mysql, "readinessProbe");
    }

    @Test
    @DisplayName("application과 migration Job은 runtime·DDL credential 경계를 분리한다")
    void keepsMigrationCredentialOutsideLongRunningApplication() {
        Map<String, Object> deployment = loadYaml(
                "deploy/k8s/base/app-deployment.yaml"
        );
        Map<String, Object> job = loadYaml(
                "deploy/k8s/base/database-migration-job.yaml"
        );
        Properties applicationConfig = loadProperties(
                "deploy/k8s/overlays/private-server/app-config.properties"
        );
        Map<String, Object> applicationPodSpec = nested(
                deployment,
                "spec",
                "template",
                "spec"
        );
        Map<String, Object> application = named(
                objectList(applicationPodSpec.get("containers")),
                "application"
        );
        Map<String, Object> podSpec = nested(job, "spec", "template", "spec");
        Map<String, Object> migration = named(
                objectList(podSpec.get("containers")),
                "migration"
        );

        assertThat(nested(job, "metadata", "labels"))
                .containsEntry(
                        "app.kubernetes.io/component",
                        "database-migration"
                );

        assertThat(configMapName(environment(application, "BATON_GO_DB_USERNAME")))
                .isEqualTo("baton-go-database-identity");
        assertThat(configMapKey(environment(application, "BATON_GO_DB_USERNAME")))
                .isEqualTo("BATON_GO_DB_USERNAME");
        assertThat(secretName(environment(application, "BATON_GO_DB_URL")))
                .isEqualTo("baton-go-database-client-config");
        assertThat(secretKey(environment(application, "BATON_GO_DB_URL")))
                .isEqualTo("BATON_GO_DB_URL");
        assertThat(secretName(environment(application, "BATON_GO_DB_PASSWORD")))
                .isEqualTo("baton-go-database-runtime-credentials");
        assertThat(secretKey(environment(application, "BATON_GO_DB_PASSWORD")))
                .isEqualTo("BATON_GO_DB_PASSWORD");
        assertThat(environmentSecretNames(application))
                .doesNotContain(
                        "baton-go-database-migration-credentials",
                        "baton-go-database-bootstrap-credentials"
                );
        assertNoSecretEnvironmentSources(application);
        assertThat(secretVolumeNames(applicationPodSpec))
                .containsExactly("baton-go-mysql-client-tls");
        assertThat(applicationConfig.getProperty("SPRING_FLYWAY_ENABLED"))
                .isEqualTo("false");

        assertThat(stringList(migration.get("args")))
                .containsExactly("--baton-go.migration-only=true");
        assertThat(environment(migration, "SPRING_FLYWAY_ENABLED").get("value"))
                .isEqualTo("true");
        assertThat(secretName(environment(migration, "BATON_GO_DB_URL")))
                .isEqualTo("baton-go-database-client-config");
        assertThat(secretKey(environment(migration, "BATON_GO_DB_URL")))
                .isEqualTo("BATON_GO_DB_URL");
        assertThat(configMapName(environment(migration, "BATON_GO_DB_USERNAME")))
                .isEqualTo("baton-go-database-identity");
        assertThat(configMapKey(environment(migration, "BATON_GO_DB_USERNAME")))
                .isEqualTo("BATON_GO_DB_MIGRATION_USERNAME");
        assertThat(secretName(environment(migration, "BATON_GO_DB_PASSWORD")))
                .isEqualTo("baton-go-database-migration-credentials");
        assertThat(secretKey(environment(migration, "BATON_GO_DB_PASSWORD")))
                .isEqualTo("BATON_GO_DB_MIGRATION_PASSWORD");
        assertThat(environmentNames(migration))
                .doesNotContain("BATON_GO_DB_ROOT_PASSWORD", "BATON_GO_MANAGEMENT_TOKEN");
        assertThat(environmentSecretNames(migration))
                .doesNotContain(
                        "baton-go-database-runtime-credentials",
                        "baton-go-database-bootstrap-credentials",
                        "baton-go-runtime-credentials"
                );
        assertNoSecretEnvironmentSources(migration);
        assertThat(secretVolumeNames(podSpec))
                .containsExactly("baton-go-mysql-client-tls");
        assertThat(mountNames(migration)).containsExactlyInAnyOrder("tmp", "mysql-client-tls");

        Map<String, Object> securityContext = nested(migration, "securityContext");
        assertThat(securityContext)
                .containsEntry("readOnlyRootFilesystem", true)
                .containsEntry("allowPrivilegeEscalation", false);
        assertThat(stringList(nested(securityContext, "capabilities").get("drop")))
                .containsExactly("ALL");
    }

    @Test
    @DisplayName("private overlay는 preflight와 MySQL 본체를 같은 immutable digest로 변환한다")
    void pinsMySqlImageAndGeneratesVersionedPreflightConfig() {
        Map<String, Object> base = loadYaml("deploy/k8s/base/kustomization.yaml");
        Map<String, Object> overlay = loadYaml(
                "deploy/k8s/overlays/private-server/kustomization.yaml"
        );

        Map<String, Object> preflightGenerator = named(
                objectList(base.get("configMapGenerator")),
                "baton-go-mysql-init-preflight"
        );
        Map<String, Object> applicationConfigGenerator = named(
                objectList(overlay.get("configMapGenerator")),
                "baton-go-config"
        );
        assertThat(stringList(base.get("resources")))
                .contains(
                        "app-deployment.yaml",
                        "database-migration-job.yaml",
                        "mysql-statefulset.yaml"
                );
        assertThat(stringList(preflightGenerator.get("files")))
                .containsExactly("mysql-init-preflight.sh");
        assertThat(stringList(applicationConfigGenerator.get("envs")))
                .containsExactly("app-config.properties");

        Map<String, Object> mysqlImage = named(
                objectList(overlay.get("images")),
                "mysql"
        );
        assertThat(mysqlImage).doesNotContainKey("newTag");
        assertThat(mysqlImage)
                .containsEntry("newName", "mysql")
                .containsEntry("digest", MYSQL_PRIVATE_DIGEST);
    }

    private static void assertSameDatabaseEnvironment(
            Map<String, Object> preflight,
            Map<String, Object> mysql
    ) {
        for (String name : List.of(
                "MYSQL_DATABASE",
                "MYSQL_USER",
                "MYSQL_PASSWORD",
                "BATON_GO_DB_USERNAME",
                "BATON_GO_DB_PASSWORD",
                "MYSQL_ROOT_PASSWORD"
        )) {
            assertThat(environment(mysql, name))
                    .as("MySQL main container environment %s", name)
                    .isEqualTo(environment(preflight, name));
        }
    }

    private static void assertTlsProbe(
            Map<String, Object> mysql,
            String probeName
    ) {
        Map<String, Object> probe = nested(mysql, probeName, "exec");
        String command = String.join(" ", stringList(probe.get("command")));
        assertThat(command)
                .contains("--protocol=TCP")
                .contains("--host=127.0.0.1")
                .contains("--ssl-mode=REQUIRED")
                .contains("--user=\"${BATON_GO_DB_USERNAME}\"")
                .contains("--execute='SELECT 1'");
    }

    private static Set<String> environmentNames(Map<String, Object> container) {
        return objectList(container.get("env")).stream()
                .map(environment -> environment.get("name").toString())
                .collect(Collectors.toSet());
    }

    private static Set<String> environmentSecretNames(Map<String, Object> container) {
        return objectList(container.get("env")).stream()
                .filter(environment -> environment.get("valueFrom") instanceof Map<?, ?>)
                .map(environment -> nested(environment, "valueFrom"))
                .filter(valueFrom -> valueFrom.get("secretKeyRef") instanceof Map<?, ?>)
                .map(valueFrom -> nested(valueFrom, "secretKeyRef").get("name").toString())
                .collect(Collectors.toSet());
    }

    private static void assertNoSecretEnvironmentSources(Map<String, Object> container) {
        Object environmentSources = container.get("envFrom");
        if (environmentSources == null) {
            return;
        }
        assertThat(objectList(environmentSources)).allSatisfy(source ->
                assertThat(source).doesNotContainKey("secretRef")
        );
    }

    private static Set<String> secretVolumeNames(Map<String, Object> podSpec) {
        return objectList(podSpec.get("volumes")).stream()
                .filter(volume -> volume.get("secret") instanceof Map<?, ?>)
                .map(volume -> nested(volume, "secret").get("secretName").toString())
                .collect(Collectors.toSet());
    }

    private static Map<String, Object> environment(
            Map<String, Object> container,
            String name
    ) {
        return named(objectList(container.get("env")), name);
    }

    private static String secretName(Map<String, Object> environment) {
        return nested(environment, "valueFrom", "secretKeyRef")
                .get("name")
                .toString();
    }

    private static String secretKey(Map<String, Object> environment) {
        return nested(environment, "valueFrom", "secretKeyRef")
                .get("key")
                .toString();
    }

    private static String configMapName(Map<String, Object> environment) {
        return nested(environment, "valueFrom", "configMapKeyRef")
                .get("name")
                .toString();
    }

    private static String configMapKey(Map<String, Object> environment) {
        return nested(environment, "valueFrom", "configMapKeyRef")
                .get("key")
                .toString();
    }

    private static Set<String> mountNames(Map<String, Object> container) {
        return objectList(container.get("volumeMounts")).stream()
                .map(volume -> volume.get("name").toString())
                .collect(Collectors.toSet());
    }

    private static Map<String, Object> named(
            List<Map<String, Object>> values,
            String name
    ) {
        return values.stream()
                .filter(value -> name.equals(value.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("manifest item not found: " + name));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectList(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(
            Map<String, Object> root,
            String... keys
    ) {
        Map<String, Object> current = root;
        for (String key : keys) {
            Object value = current.get(key);
            assertThat(value).as("manifest key %s", key).isInstanceOf(Map.class);
            current = (Map<String, Object>) value;
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String relativePath) {
        Path path = REPOSITORY_ROOT.resolve(relativePath);
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            assertThat(document).isInstanceOf(Map.class);
            return (Map<String, Object>) document;
        } catch (IOException exception) {
            throw new IllegalStateException("Kubernetes manifest를 읽을 수 없습니다", exception);
        }
    }

    private static Properties loadProperties(String relativePath) {
        Path path = REPOSITORY_ROOT.resolve(relativePath);
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return properties;
        } catch (IOException exception) {
            throw new IllegalStateException("Kubernetes properties를 읽을 수 없습니다", exception);
        }
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("batonGo.repositoryRoot");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("BATON GO repository root test 설정이 없습니다");
        }
        return Path.of(configured);
    }
}
