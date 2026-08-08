package com.personal.batongo.bootstrap.guard;

import com.personal.batongo.adapter.out.external.link.LinkCodeProperties;
import com.personal.batongo.adapter.out.external.link.SecureLinkCodeAdapter;
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

public final class LinkCodeKeyGuardBindingCli {

    static final int EXIT_USAGE = 2;
    static final int EXIT_VERIFICATION_FAILED = 3;
    private static final String CONFIRMATION = "--confirm-writers-stopped";
    private static final String PLACEHOLDER_PREFIX = "replace-with-";

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
            RuntimeConfiguration configuration = RuntimeConfiguration.from(environment);
            LinkCodePort linkCodePort = new SecureLinkCodeAdapter(
                    new LinkCodeProperties(configuration.linkCodeSecret())
            );
            try (Connection connection = DriverManager.getConnection(
                    configuration.jdbcUrl(),
                    configuration.username(),
                    configuration.password()
            )) {
                connection.setAutoCommit(false);
                try {
                    BindingResult result = new ExistingDatabaseLinkCodeKeyBinder().bind(
                            connection,
                            linkCodePort,
                            canaryIdempotencyKey
                    );
                    connection.commit();
                    standardOutput.println(result == BindingResult.BOUND
                            ? "링크 코드 키 guard 결합을 완료했습니다"
                            : "링크 코드 키 guard가 같은 identity에 이미 결합되어 있습니다");
                    return 0;
                } catch (RuntimeException | SQLException exception) {
                    rollback(connection);
                    throw exception;
                }
            }
        } catch (RuntimeException | SQLException exception) {
            standardError.println(
                    "링크 코드 키 guard 결합에 실패했습니다. 설정, canary와 DB 상태를 확인하세요"
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

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 실패 응답은 원본 JDBC 세부 정보를 노출하지 않는다.
        }
    }

    private record RuntimeConfiguration(
            String jdbcUrl,
            String username,
            String password,
            String linkCodeSecret
    ) {

        private static RuntimeConfiguration from(Map<String, String> environment) {
            String secret = require(environment, "BATON_GO_LINK_CODE_SECRET");
            if (secret.startsWith(PLACEHOLDER_PREFIX)) {
                throw new IllegalArgumentException("공개 예시 비밀은 사용할 수 없습니다");
            }
            return new RuntimeConfiguration(
                    require(environment, "BATON_GO_DB_URL"),
                    require(environment, "BATON_GO_DB_USERNAME"),
                    require(environment, "BATON_GO_DB_PASSWORD"),
                    secret
            );
        }

        private static String require(Map<String, String> environment, String name) {
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
