package com.personal.batongo.bootstrap.guard;

import com.personal.batongo.adapter.out.external.link.LinkCodeProperties;
import com.personal.batongo.adapter.out.external.link.SecureLinkCodeAdapter;
import com.personal.batongo.application.link.CreationIdempotencyKey;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongo.bootstrap.guard.ExistingDatabaseLinkCodeKeyBinder.BindingResult;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 기존 데이터베이스에 HMAC 키 정보를 처음 등록하는 일회성 CLI입니다. */
public final class LinkCodeKeyGuardBindingCli {

    private static final int EXIT_USAGE = 2;
    private static final int EXIT_VERIFICATION_FAILED = 3;
    private static final String CONFIRMATION = "--confirm-writers-stopped";

    public static void main(String[] args) {
        LinkCodeKeyGuardBindingCli cli = new LinkCodeKeyGuardBindingCli();
        String canaryIdempotencyKey = args.length == 1 && CONFIRMATION.equals(args[0])
                ? readCanaryIdempotencyKey()
                : null;
        int exitCode = cli.run(
                args,
                System.getenv(),
                canaryIdempotencyKey,
                System.out,
                System.err
        );
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    int run(
            String[] args,
            Map<String, String> environment,
            String canaryIdempotencyKey,
            PrintStream standardOutput,
            PrintStream standardError
    ) {
        if (args.length != 1 || !CONFIRMATION.equals(args[0])) {
            standardError.println(
                    "사용법: baton-go-guard-binding.jar --confirm-writers-stopped"
            );
            return EXIT_USAGE;
        }

        try {
            CreationIdempotencyKey canary =
                    CreationIdempotencyKey.parseRequest(canaryIdempotencyKey);
            RuntimeConfiguration configuration = RuntimeConfiguration.from(environment);
            LinkCodePort linkCodePort = new SecureLinkCodeAdapter(
                    configuration.linkCodeProperties()
            );
            Properties jdbcProperties = new Properties();
            jdbcProperties.setProperty("user", configuration.username());
            jdbcProperties.setProperty("password", configuration.password());
            jdbcProperties.setProperty("connectTimeout", "3000");
            jdbcProperties.setProperty("socketTimeout", "5000");
            // 개인 키가 없는 공개 CA 저장소의 고정 비밀번호이며 애플리케이션 설정과 같다.
            jdbcProperties.setProperty("trustCertificateKeyStorePassword", "baton-go-public-ca-v1");
            try (Connection connection = DriverManager.getConnection(
                    configuration.jdbcUrl(),
                    jdbcProperties
            )) {
                BindingResult result = new ExistingDatabaseLinkCodeKeyBinder().bind(
                        new SingleConnectionDataSource(connection, true),
                        linkCodePort,
                        canary
                );
                standardOutput.println(result == BindingResult.BOUND
                        ? "HMAC 키 정보를 DB에 등록했습니다"
                        : "동일한 HMAC 키 정보가 이미 등록되어 있습니다");
                return 0;
            }
        } catch (RuntimeException | SQLException exception) {
            standardError.println(
                    "HMAC 키 정보 등록에 실패했습니다. 설정, 검증용 요청과 DB 상태를 확인하세요"
            );
            return EXIT_VERIFICATION_FAILED;
        }
    }

    private static String readCanaryIdempotencyKey() {
        Console console = System.console();
        if (console != null) {
            char[] value = console.readPassword("Canary Idempotency-Key: ");
            if (value == null) {
                return null;
            }
            try {
                return new String(value);
            } finally {
                Arrays.fill(value, '\0');
            }
        }
        try {
            return new BufferedReader(new InputStreamReader(System.in)).readLine();
        } catch (IOException exception) {
            return null;
        }
    }

    record RuntimeConfiguration(
            String jdbcUrl,
            String username,
            String password,
            LinkCodeProperties linkCodeProperties
    ) {

        static RuntimeConfiguration from(Map<String, String> environment) {
            String secret = environment.get("BATON_GO_LINK_CODE_SECRET");
            LinkCodeProperties linkCodeProperties = new LinkCodeProperties(secret);
            return new RuntimeConfiguration(
                    requireNonBlank(environment, "BATON_GO_DB_URL"),
                    requireNonBlank(environment, "BATON_GO_DB_USERNAME"),
                    requireNonBlank(environment, "BATON_GO_DB_PASSWORD"),
                    linkCodeProperties
            );
        }

        private static String requireNonBlank(Map<String, String> environment, String name) {
            String value = environment.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " 설정은 필수입니다");
            }
            return value;
        }

        @Override
        public String toString() {
            return "RuntimeConfiguration[redacted]";
        }
    }
}
