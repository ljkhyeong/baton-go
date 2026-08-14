package com.personal.batongo;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.RemediationCommand;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.RemediationResult;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("mysql")
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "baton-go.management.token=test-management-token-that-is-long-enough",
        "baton-go.link-code.secret=test-link-code-secret-that-is-separate-and-long-enough",
        "baton-go.public-base-url=https://go.example",
        "baton-go.targets.baton-base-url=https://baton.example",
        "baton-go.targets.round-base-url=https://baton.example",
        "baton-go.target-contract-operations.enabled=true",
        "baton-go.target-contract-operations.private-ingress-confirmed=true"
})
class TargetContractOperationsIntegrationTest {

    private static final String MANAGEMENT_TOKEN =
            "test-management-token-that-is-long-enough";
    private static final UUID VALID_LINK_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID INVALID_LINK_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID UNKNOWN_ENUM_LINK_ID =
            UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
    private static final String VALID_TARGET_PATH =
            "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                    + "/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a";
    private static final String INVALID_TARGET_PATH = "/teams/legacy-target";
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T00:00:00Z");

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.10");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TargetContractOperationsUseCase targetContractOperationsUseCase;

    @BeforeEach
    void cleanStoredLinks() {
        jdbcTemplate.update("DELETE FROM link_creation_requests");
        jdbcTemplate.update("DELETE FROM smart_links");
    }

    @Test
    @DisplayName("inventory는 알 수 없는 enum을 포함한 저장 행을 raw target 노출 없이 페이지로 분류한다")
    void inventoriesStoredTargetsWithoutExposingRawValues() throws Exception {
        insertStoredLink(
                VALID_LINK_ID,
                "1".repeat(64),
                "BATON",
                VALID_TARGET_PATH,
                "NAVIGATION",
                0,
                true
        );
        insertStoredLink(
                INVALID_LINK_ID,
                "2".repeat(64),
                "BATON",
                INVALID_TARGET_PATH,
                "NAVIGATION",
                3,
                false
        );
        insertStoredLink(
                UNKNOWN_ENUM_LINK_ID,
                "3".repeat(64),
                "LEGACY",
                VALID_TARGET_PATH,
                "NAVIGATION",
                7,
                true
        );

        mockMvc.perform(get(
                        "/api/v1/operations/link-target-contract-v1/inventory"
                )
                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.contractVersion").value("v1"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].linkId").value(VALID_LINK_ID.toString()))
                .andExpect(jsonPath("$.items[0].compliance").value("COMPLIANT"))
                .andExpect(jsonPath("$.items[0].remediationState").value("NOT_REQUIRED"))
                .andExpect(jsonPath("$.items[0].creationRequestState").value("PRESENT"))
                .andExpect(jsonPath("$.items[1].linkId").value(INVALID_LINK_ID.toString()))
                .andExpect(jsonPath("$.items[1].compliance").value("NON_COMPLIANT"))
                .andExpect(jsonPath("$.items[1].remediationState").value("UNREVOKED"))
                .andExpect(jsonPath("$.items[1].creationRequestState").value("MISSING"))
                .andExpect(jsonPath("$.items[1].version").value(3))
                .andExpect(jsonPath("$.nextAfterLinkId").value(INVALID_LINK_ID.toString()))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.items[0].targetPath").doesNotExist())
                .andExpect(jsonPath("$.items[0].targetSystem").doesNotExist())
                .andExpect(jsonPath("$.items[0].purpose").doesNotExist())
                .andExpect(jsonPath("$.items[0].codeHash").doesNotExist())
                .andExpect(jsonPath("$.items[0].shortUrl").doesNotExist())
                .andExpect(content().string(not(containsString(INVALID_TARGET_PATH))))
                .andExpect(content().string(not(containsString("LEGACY"))));

        mockMvc.perform(get(
                        "/api/v1/operations/link-target-contract-v1/inventory"
                )
                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                        .param("afterLinkId", INVALID_LINK_ID.toString())
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].linkId")
                        .value(UNKNOWN_ENUM_LINK_ID.toString()))
                .andExpect(jsonPath("$.items[0].compliance").value("NON_COMPLIANT"))
                .andExpect(jsonPath("$.items[0].creationRequestState").value("PRESENT"))
                .andExpect(jsonPath("$.nextAfterLinkId").value(nullValue()))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(content().string(not(containsString("LEGACY"))));

        mockMvc.perform(get("/api/v1/links/{linkId}", INVALID_LINK_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString(INVALID_TARGET_PATH))))
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
        mockMvc.perform(put("/api/v1/links/{linkId}/revocation", INVALID_LINK_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString(INVALID_TARGET_PATH))))
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
    }

    @Test
    @DisplayName("unknown enum 링크의 remediation은 version을 재검증하고 최초 폐기 시각을 보존한다")
    void remediatesUnknownEnumIdempotentlyWithVersionCheck() throws Exception {
        insertStoredLink(
                UNKNOWN_ENUM_LINK_ID,
                "4".repeat(64),
                "LEGACY",
                INVALID_TARGET_PATH,
                "NAVIGATION",
                5,
                true
        );

        mockMvc.perform(put(
                        "/api/v1/operations/link-target-contract-v1/links/{linkId}/revocation",
                        UNKNOWN_ENUM_LINK_ID
                )
                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REMEDIATION_STALE"));
        assertThat(storedRevokedAt(UNKNOWN_ENUM_LINK_ID)).isNull();
        assertThat(storedVersion(UNKNOWN_ENUM_LINK_ID)).isEqualTo(5L);

        mockMvc.perform(put(
                        "/api/v1/operations/link-target-contract-v1/links/{linkId}/revocation",
                        UNKNOWN_ENUM_LINK_ID
                )
                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":5}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.linkId").value(UNKNOWN_ENUM_LINK_ID.toString()))
                .andExpect(jsonPath("$.contractVersion").value("v1"))
                .andExpect(jsonPath("$.remediationState").value("REVOKED"))
                .andExpect(jsonPath("$.alreadyRevoked").value(false));

        Instant firstRevokedAt = storedRevokedAt(UNKNOWN_ENUM_LINK_ID);
        assertThat(firstRevokedAt).isNotNull();
        assertThat(storedVersion(UNKNOWN_ENUM_LINK_ID)).isEqualTo(6L);
        assertThat(storedCreationRequestCount(UNKNOWN_ENUM_LINK_ID)).isOne();

        mockMvc.perform(put(
                        "/api/v1/operations/link-target-contract-v1/links/{linkId}/revocation",
                        UNKNOWN_ENUM_LINK_ID
                )
                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedAt").value(firstRevokedAt.toString()))
                .andExpect(jsonPath("$.alreadyRevoked").value(true));
        assertThat(storedRevokedAt(UNKNOWN_ENUM_LINK_ID)).isEqualTo(firstRevokedAt);
        assertThat(storedVersion(UNKNOWN_ENUM_LINK_ID)).isEqualTo(6L);

        mockMvc.perform(get("/api/v1/links/{linkId}", UNKNOWN_ENUM_LINK_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
    }

    @Test
    @DisplayName("동시 remediation은 MySQL row lock으로 최초 폐기와 한 번의 version 증가에 수렴한다")
    void serializesConcurrentRemediation() throws Exception {
        insertStoredLink(
                UNKNOWN_ENUM_LINK_ID,
                "6".repeat(64),
                "LEGACY",
                INVALID_TARGET_PATH,
                "NAVIGATION",
                9,
                true
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<RemediationResult> first = executor.submit(() -> remediateAfterStart(
                    ready,
                    start
            ));
            Future<RemediationResult> second = executor.submit(() -> remediateAfterStart(
                    ready,
                    start
            ));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<RemediationResult> results = List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)
            );
            assertThat(results)
                    .extracting(RemediationResult::alreadyRevoked)
                    .containsExactlyInAnyOrder(false, true);
            assertThat(results)
                    .extracting(RemediationResult::revokedAt)
                    .containsOnly(results.getFirst().revokedAt());
            assertThat(storedRevokedAt(UNKNOWN_ENUM_LINK_ID))
                    .isEqualTo(results.getFirst().revokedAt());
            assertThat(storedVersion(UNKNOWN_ENUM_LINK_ID)).isEqualTo(10L);
            assertThat(storedCreationRequestCount(UNKNOWN_ENUM_LINK_ID)).isOne();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("실제 Spring JSON 조립은 문자열 expectedVersion을 변경 전에 거부한다")
    void rejectsStringVersionWithConfiguredSpringJsonMapper() throws Exception {
        insertStoredLink(
                INVALID_LINK_ID,
                "8".repeat(64),
                "BATON",
                INVALID_TARGET_PATH,
                "NAVIGATION",
                7,
                true
        );

        mockMvc.perform(put(
                        "/api/v1/operations/link-target-contract-v1/links/{linkId}/revocation",
                        INVALID_LINK_ID
                )
                        .header(HttpHeaders.AUTHORIZATION, bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":\"7\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(storedRevokedAt(INVALID_LINK_ID)).isNull();
        assertThat(storedVersion(INVALID_LINK_ID)).isEqualTo(7L);
    }

    private RemediationResult remediateAfterStart(
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("동시 remediation 시작 신호를 기다리지 못했습니다");
        }
        return targetContractOperationsUseCase.remediate(new RemediationCommand(
                UNKNOWN_ENUM_LINK_ID,
                9L
        ));
    }

    private void insertStoredLink(
            UUID linkId,
            String codeHash,
            String targetSystem,
            String targetPath,
            String purpose,
            long version,
            boolean withCreationRequest
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO smart_links (
                            id,
                            code_hash,
                            target_system,
                            target_path,
                            purpose,
                            not_before,
                            expires_at,
                            revoked_at,
                            created_at,
                            version
                        ) VALUES (UUID_TO_BIN(?), ?, ?, ?, ?, NULL, NULL, NULL, ?, ?)
                        """,
                linkId.toString(),
                codeHash,
                targetSystem,
                targetPath,
                purpose,
                Timestamp.from(CREATED_AT),
                version
        );
        if (withCreationRequest) {
            jdbcTemplate.update(
                    """
                            INSERT INTO link_creation_requests (
                                idempotency_key_hash,
                                link_id,
                                created_at
                            ) VALUES (?, UUID_TO_BIN(?), ?)
                            """,
                    "%064x".formatted(linkId.getLeastSignificantBits() & Long.MAX_VALUE),
                    linkId.toString(),
                    Timestamp.from(CREATED_AT)
            );
        }
    }

    private String bearerToken() {
        return "Bearer " + MANAGEMENT_TOKEN;
    }

    private Instant storedRevokedAt(UUID linkId) {
        return jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM smart_links WHERE id = UUID_TO_BIN(?)",
                (resultSet, rowNumber) -> {
                    LocalDateTime value = resultSet.getObject(
                            "revoked_at",
                            LocalDateTime.class
                    );
                    return value == null ? null : value.toInstant(ZoneOffset.UTC);
                },
                linkId.toString()
        );
    }

    private long storedVersion(UUID linkId) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM smart_links WHERE id = UUID_TO_BIN(?)",
                Long.class,
                linkId.toString()
        );
    }

    private long storedCreationRequestCount(UUID linkId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM link_creation_requests WHERE link_id = UUID_TO_BIN(?)",
                Long.class,
                linkId.toString()
        );
    }
}
