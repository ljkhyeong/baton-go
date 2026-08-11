package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.batongo.application.link.CreationIdempotencyKey;
import com.personal.batongo.application.link.CreationTimeStoragePolicy;
import com.personal.batongo.application.link.PublicLinkOrigin;
import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreatedLinkResult;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.LinkResult;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongo.application.link.port.out.LinkCreationReservationPort;
import com.personal.batongo.application.link.port.out.PublicLinkOriginPort;
import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.SmartLink;
import com.personal.batongo.domain.link.TargetSystem;
import com.personal.batongo.domain.link.TrustedTargetPolicy;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("mysql")
@Testcontainers
@AutoConfigureMockMvc
@Import(LinkCreationIdempotencyIntegrationTest.ConcurrencyTestConfiguration.class)
@SpringBootTest(properties = {
        "baton-go.management.token=test-management-token-that-is-long-enough",
        "baton-go.link-code.secret=test-link-code-secret-that-is-separate-and-long-enough",
        "baton-go.public-base-url=https://go.example",
        "baton-go.targets.baton-base-url=https://baton.example",
        "baton-go.targets.round-base-url=https://baton.example"
})
class LinkCreationIdempotencyIntegrationTest {

    private static final int CONCURRENCY = 8;
    private static final String IDEMPOTENCY_KEY =
            "8e448211-66ae-44ab-9888-c4960648c22b";
    private static final String ROLLBACK_IDEMPOTENCY_KEY =
            "7606bb52-2837-4359-bca4-d7f295b64fe4";
    private static final String FAR_FUTURE_IDEMPOTENCY_KEY =
            "96eb6b91-8390-422b-9ad7-a9b980809af8";
    private static final String CANONICAL_BATON_TARGET =
            "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                    + "/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a";
    private static final Instant FAR_FUTURE_NOW =
            Instant.parse("2040-06-01T12:34:56.123456Z");
    private static final Instant FAR_FUTURE_NOT_BEFORE =
            Instant.parse("2040-06-01T13:00:00.654321Z");
    private static final Instant FAR_FUTURE_EXPIRES_AT =
            Instant.parse("2040-06-02T13:00:00.987654Z");

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.10");

    @Autowired
    private SmartLinkUseCase smartLinkUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SmartLinkRepository smartLinkRepository;

    @Autowired
    private LinkCodePort linkCodePort;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ControllableReservationPort controllableReservationPort;

    @Autowired
    private ControllablePublicLinkOriginPort controllablePublicLinkOriginPort;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Transactional
    @DisplayName("할당 UUID와 null 버전의 새 링크는 persist 대상으로 저장된다")
    void persistsNewLinkWithAssignedId() {
        SmartLink candidate = SmartLink.create(
                UUID.fromString("7f7386b7-8a34-46c9-ae20-606d95a63bb2"),
                "a".repeat(64),
                TrustedTargetPolicy.requireAllowed(
                        TargetSystem.BATON,
                        LinkPurpose.NAVIGATION,
                        CANONICAL_BATON_TARGET
                ),
                null,
                null,
                Instant.parse("2026-07-31T00:00:00Z")
        );

        SmartLink saved = smartLinkRepository.save(candidate);
        entityManager.flush();

        assertThat(saved).isSameAs(candidate);
        assertThat(entityManager.contains(candidate)).isTrue();
    }

    @Test
    @DisplayName("Flyway와 JPA는 2040년 링크의 생성 재생 폐기 시각을 마이크로초까지 보존한다")
    void persistsCreationReplayAndRevocationAfterTimestampLimit() {
        CreateLinkCommand command = new CreateLinkCommand(
                new CreationIdempotencyKey(FAR_FUTURE_IDEMPOTENCY_KEY),
                TargetSystem.ROUND,
                "/room/wxyz-2345-6789",
                LinkPurpose.MEETING_ENTRY,
                FAR_FUTURE_NOT_BEFORE,
                FAR_FUTURE_EXPIRES_AT
        );

        CreatedLinkResult created = smartLinkUseCase.createLink(command);
        CreatedLinkResult replayed = smartLinkUseCase.createLink(command);

        assertThat(created.replayed()).isFalse();
        assertThat(created.link().createdAt()).isEqualTo(FAR_FUTURE_NOW);
        assertThat(created.link().notBefore()).isEqualTo(FAR_FUTURE_NOT_BEFORE);
        assertThat(created.link().expiresAt()).isEqualTo(FAR_FUTURE_EXPIRES_AT);
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.link()).isEqualTo(created.link());
        assertThat(replayed.shortUrl()).isEqualTo(created.shortUrl());
        assertThat(created.shortUrl().toString())
                .matches("https://go\\.example/l/[A-Za-z0-9_-]{22}");
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT public_origin
                        FROM link_creation_requests
                        WHERE link_id = UUID_TO_BIN(?)
                        """,
                String.class,
                created.link().id().toString()
        )).isEqualTo("https://go.example");

        LinkResult revoked = smartLinkUseCase.revokeLink(created.link().id());
        CreatedLinkResult replayedAfterRevocation = smartLinkUseCase.createLink(command);

        assertThat(revoked.revokedAt()).isEqualTo(FAR_FUTURE_NOW);
        assertThat(replayedAfterRevocation.replayed()).isTrue();
        assertThat(replayedAfterRevocation.link().revokedAt()).isEqualTo(FAR_FUTURE_NOW);

        StoredAbsoluteTimes stored = storedAbsoluteTimes(created.link().id());
        assertThat(stored).isEqualTo(new StoredAbsoluteTimes(
                FAR_FUTURE_NOT_BEFORE,
                FAR_FUTURE_EXPIRES_AT,
                FAR_FUTURE_NOW,
                FAR_FUTURE_NOW,
                FAR_FUTURE_NOW
        ));
    }

    @Test
    @DisplayName("API 지원 시각의 양쪽 경계는 MySQL raw 날짜와 재생 응답에서 그대로 보존된다")
    void preservesSupportedTimeBoundariesInMysqlRawValuesAndReplay() throws Exception {
        String idempotencyKey = "5b2355cf-8647-464e-a633-0f8c50ec169c";
        String targetPath = "/room/wxyz-abcd-2345";
        String requestBody = """
                {
                  "targetSystem": "ROUND",
                  "targetPath": "%s",
                  "purpose": "MEETING_ENTRY",
                  "notBefore": "%s",
                  "expiresAt": "%s"
                }
                """.formatted(
                targetPath,
                CreationTimeStoragePolicy.MINIMUM,
                CreationTimeStoragePolicy.MAXIMUM
        );

        var createdResponse = mockMvc.perform(post("/api/v1/links")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer test-management-token-that-is-long-enough"
                        )
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.notBefore")
                        .value(CreationTimeStoragePolicy.MINIMUM.toString()))
                .andExpect(jsonPath("$.expiresAt")
                        .value(CreationTimeStoragePolicy.MAXIMUM.toString()))
                .andReturn()
                .getResponse();
        String location = createdResponse.getHeader(HttpHeaders.LOCATION);
        assertThat(location).isNotNull();
        UUID linkId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

        mockMvc.perform(post("/api/v1/links")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer test-management-token-that-is-long-enough"
                        )
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(linkId.toString()))
                .andExpect(jsonPath("$.notBefore")
                        .value(CreationTimeStoragePolicy.MINIMUM.toString()))
                .andExpect(jsonPath("$.expiresAt")
                        .value(CreationTimeStoragePolicy.MAXIMUM.toString()));

        RawBoundaryTimes rawTimes = jdbcTemplate.queryForObject(
                """
                        SELECT DATE_FORMAT(
                                   not_before,
                                   '%Y-%m-%dT%H:%i:%s.%fZ'
                               ) AS not_before,
                               DATE_FORMAT(
                                   expires_at,
                                   '%Y-%m-%dT%H:%i:%s.%fZ'
                               ) AS expires_at
                        FROM smart_links
                        WHERE id = UUID_TO_BIN(?)
                        """,
                (resultSet, rowNumber) -> new RawBoundaryTimes(
                        resultSet.getString("not_before"),
                        resultSet.getString("expires_at")
                ),
                linkId.toString()
        );
        assertThat(rawTimes).isEqualTo(new RawBoundaryTimes(
                "1582-10-15T00:00:00.000000Z",
                "9999-12-31T23:59:59.999999Z"
        ));
    }

    @Test
    @DisplayName("V4 마이그레이션은 모든 절대 시각 열을 DATETIME(6)으로 바꾸고 제약과 인덱스를 유지한다")
    void migratesAbsoluteTimeColumnsWithoutDroppingSchemaObjects() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '4' AND success = 1",
                Long.class
        )).isOne();

        List<TimeColumn> timeColumns = jdbcTemplate.query(
                """
                        SELECT table_name,
                               column_name,
                               data_type,
                               datetime_precision,
                               is_nullable
                        FROM information_schema.columns
                        WHERE table_schema = DATABASE()
                          AND (
                              (table_name = 'smart_links' AND column_name IN (
                                  'not_before',
                                  'expires_at',
                                  'revoked_at',
                                  'created_at'
                              ))
                              OR (table_name = 'link_creation_requests'
                                  AND column_name = 'created_at')
                          )
                        """,
                (resultSet, rowNumber) -> new TimeColumn(
                        resultSet.getString("table_name"),
                        resultSet.getString("column_name"),
                        resultSet.getString("data_type"),
                        resultSet.getInt("datetime_precision"),
                        resultSet.getString("is_nullable")
                )
        );
        assertThat(timeColumns).containsExactlyInAnyOrder(
                new TimeColumn("smart_links", "not_before", "datetime", 6, "YES"),
                new TimeColumn("smart_links", "expires_at", "datetime", 6, "YES"),
                new TimeColumn("smart_links", "revoked_at", "datetime", 6, "YES"),
                new TimeColumn("smart_links", "created_at", "datetime", 6, "NO"),
                new TimeColumn(
                        "link_creation_requests",
                        "created_at",
                        "datetime",
                        6,
                        "NO"
                )
        );

        List<SchemaObject> constraints = schemaObjects(
                "information_schema.table_constraints",
                "constraint_name"
        );
        assertThat(constraints).contains(
                new SchemaObject("smart_links", "PRIMARY"),
                new SchemaObject("smart_links", "uk_smart_links_code_hash"),
                new SchemaObject("smart_links", "ck_smart_links_expiry_after_creation"),
                new SchemaObject("smart_links", "ck_smart_links_expiry_after_activation"),
                new SchemaObject("link_creation_requests", "PRIMARY"),
                new SchemaObject(
                        "link_creation_requests",
                        "uk_link_creation_requests_link_id"
                )
        );

        List<SchemaObject> indexes = schemaObjects(
                "information_schema.statistics",
                "index_name"
        );
        assertThat(indexes).contains(
                new SchemaObject("smart_links", "PRIMARY"),
                new SchemaObject("smart_links", "uk_smart_links_code_hash"),
                new SchemaObject("smart_links", "ix_smart_links_expiry"),
                new SchemaObject("link_creation_requests", "PRIMARY"),
                new SchemaObject(
                        "link_creation_requests",
                        "uk_link_creation_requests_link_id"
                )
        );
    }

    @Test
    @DisplayName("실제 Spring 조립은 관리 인증과 request ID filter 순서를 적용한다")
    void assemblesManagementAuthenticationFilters() throws Exception {
        UUID missingLinkId = UUID.fromString("27e436c8-e696-4477-9fa2-45e4cf37a942");

        mockMvc.perform(get("/api/v1/links/{linkId}", missingLinkId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"baton-go-management\""
                ))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        mockMvc.perform(get("/api/v1/links/{linkId}", missingLinkId)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "bEaReR test-management-token-that-is-long-enough"
                        ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
    }

    @Test
    @DisplayName("정의되지 않은 링크 생성 필드는 저장 전에 400으로 거부한다")
    void rejectsUnknownCreationFieldBeforePersistence() throws Exception {
        String targetPath = "/room/2345-6789-abcd";

        mockMvc.perform(post("/api/v1/links")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer test-management-token-that-is-long-enough"
                        )
                        .header(
                                "Idempotency-Key",
                                "09ef0b69-9004-47ed-a056-5f6b720dce23"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "ROUND",
                                  "targetPath": "%s",
                                  "purpose": "MEETING_ENTRY",
                                  "expireAt": "2026-08-01T00:00:00Z"
                                }
                                """.formatted(targetPath)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM smart_links WHERE target_path = ?",
                Long.class,
                targetPath
        )).isZero();
    }

    @Test
    @DisplayName("알려진 값으로 만든 비허용 target은 링크와 예약을 남기지 않고 400으로 거부한다")
    void rejectsKnownInvalidTargetBeforePersistence() throws Exception {
        String idempotencyKey = "64fd6ee4-2559-4623-b1d9-b89167a7307f";

        mockMvc.perform(post("/api/v1/links")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer test-management-token-that-is-long-enough"
                        )
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "BATON",
                                  "targetPath": "%s",
                                  "purpose": "MEETING_ENTRY"
                                }
                                """.formatted(CANONICAL_BATON_TARGET)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_LINK"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM smart_links
                        WHERE target_system = 'BATON'
                          AND target_path = ?
                          AND purpose = 'MEETING_ENTRY'
                        """,
                Long.class,
                CANONICAL_BATON_TARGET
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM link_creation_requests
                        WHERE idempotency_key_hash = ?
                        """,
                Long.class,
                linkCodePort.hashIdempotencyKey(idempotencyKey)
        )).isZero();
    }

    @Test
    @DisplayName("과거 대문자 UUID 요청은 기존 MySQL 예약과 일치할 때 행을 늘리지 않고 재생한다")
    void replaysLegacyUppercaseUuidFromExistingMysqlReservation() throws Exception {
        String rawIdempotencyKey = "00000000-0000-7000-8000-00000000000A";
        String normalizedIdempotencyKey = rawIdempotencyKey.toLowerCase(
                java.util.Locale.ROOT
        );
        String linkId = "466d487c-e690-4bf7-b116-f99f380f1b82";
        String targetPath = "/room/efgh-jkmn-pqrs";
        jdbcTemplate.update(
                """
                        INSERT INTO smart_links (
                            id,
                            code_hash,
                            target_system,
                            target_path,
                            purpose,
                            created_at,
                            version
                        ) VALUES (UUID_TO_BIN(?), ?, 'ROUND', ?, 'MEETING_ENTRY', ?, 0)
                        """,
                linkId,
                linkCodePort.issue(normalizedIdempotencyKey).codeHash(),
                targetPath,
                FAR_FUTURE_NOW
        );
        jdbcTemplate.update(
                """
                        INSERT INTO link_creation_requests (
                            idempotency_key_hash,
                            link_id,
                            public_origin,
                            created_at
                        ) VALUES (?, UUID_TO_BIN(?), 'https://go.example', ?)
                        """,
                linkCodePort.hashIdempotencyKey(normalizedIdempotencyKey),
                linkId,
                FAR_FUTURE_NOW
        );

        mockMvc.perform(post("/api/v1/links")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer test-management-token-that-is-long-enough"
                        )
                        .header("Idempotency-Key", rawIdempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "ROUND",
                                  "targetPath": "%s",
                                  "purpose": "MEETING_ENTRY"
                                }
                                """.formatted(targetPath)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(linkId));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM smart_links WHERE target_path = ?",
                Long.class,
                targetPath
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM link_creation_requests WHERE idempotency_key_hash = ?",
                Long.class,
                linkCodePort.hashIdempotencyKey(normalizedIdempotencyKey)
        )).isOne();
    }

    @Test
    @DisplayName("공개 origin 증거가 없는 기존 MySQL 예약은 잘못 추정하지 않고 500으로 거부한다")
    void rejectsExistingMysqlReservationWithoutPublicOrigin() throws Exception {
        String idempotencyKey = "cc9d17dd-d02d-4c14-842c-afbb03887fc6";
        String linkId = "93d4229a-0edf-4d85-a769-0efb7e58c179";
        String targetPath = "/room/qrst-6789-uvwx";
        jdbcTemplate.update(
                """
                        INSERT INTO smart_links (
                            id,
                            code_hash,
                            target_system,
                            target_path,
                            purpose,
                            created_at,
                            version
                        ) VALUES (UUID_TO_BIN(?), ?, 'ROUND', ?, 'MEETING_ENTRY', ?, 0)
                        """,
                linkId,
                linkCodePort.issue(idempotencyKey).codeHash(),
                targetPath,
                FAR_FUTURE_NOW
        );
        jdbcTemplate.update(
                """
                        INSERT INTO link_creation_requests (
                            idempotency_key_hash,
                            link_id,
                            created_at
                        ) VALUES (?, UUID_TO_BIN(?), ?)
                        """,
                linkCodePort.hashIdempotencyKey(idempotencyKey),
                linkId,
                FAR_FUTURE_NOW
        );

        mockMvc.perform(post("/api/v1/links")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer test-management-token-that-is-long-enough"
                        )
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "ROUND",
                                  "targetPath": "%s",
                                  "purpose": "MEETING_ENTRY"
                                }
                                """.formatted(targetPath)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code")
                        .value("PUBLIC_LINK_ORIGIN_REPLAY_UNAVAILABLE"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("과거 UUID 요청은 기존 예약이 없으면 MySQL에 행을 남기지 않고 400으로 거부한다")
    void rejectsLegacyUuidWithoutMysqlReservation() throws Exception {
        String rawIdempotencyKey = "00000000-0000-7000-8000-00000000000B";
        String normalizedIdempotencyKey = rawIdempotencyKey.toLowerCase(
                java.util.Locale.ROOT
        );
        String targetPath = "/room/tuvw-xy23-4567";

        mockMvc.perform(post("/api/v1/links")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer test-management-token-that-is-long-enough"
                        )
                        .header("Idempotency-Key", rawIdempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "ROUND",
                                  "targetPath": "%s",
                                  "purpose": "MEETING_ENTRY"
                                }
                                """.formatted(targetPath)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM smart_links WHERE target_path = ?",
                Long.class,
                targetPath
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM link_creation_requests WHERE idempotency_key_hash = ?",
                Long.class,
                linkCodePort.hashIdempotencyKey(normalizedIdempotencyKey)
        )).isZero();
    }

    @Test
    @DisplayName("과거 나노초 요청은 기존 MySQL 예약의 마이크로초 payload와 일치할 때 재생한다")
    void replaysLegacySubMicrosecondTimeFromExistingMysqlReservation() throws Exception {
        String idempotencyKey = "61a78df8-4859-4e66-8ad9-57c3a29bd2d2";
        String targetPath = "/room/abcd-2345-efgh";
        Instant historicalExpiresAt = Instant.parse("2040-06-02T13:00:00.123456789Z");
        Instant storedExpiresAt = Instant.parse("2040-06-02T13:00:00.123456Z");
        CreatedLinkResult created = smartLinkUseCase.createLink(new CreateLinkCommand(
                new CreationIdempotencyKey(idempotencyKey),
                TargetSystem.ROUND,
                targetPath,
                LinkPurpose.MEETING_ENTRY,
                null,
                storedExpiresAt
        ));

        mockMvc.perform(post("/api/v1/links")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer test-management-token-that-is-long-enough"
                        )
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "ROUND",
                                  "targetPath": "%s",
                                  "purpose": "MEETING_ENTRY",
                                  "expiresAt": "%s"
                                }
                                """.formatted(targetPath, historicalExpiresAt)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(created.link().id().toString()))
                .andExpect(jsonPath("$.expiresAt").value(storedExpiresAt.toString()));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM smart_links WHERE target_path = ?",
                Long.class,
                targetPath
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM link_creation_requests WHERE idempotency_key_hash = ?",
                Long.class,
                linkCodePort.hashIdempotencyKey(idempotencyKey)
        )).isOne();
    }

    @Test
    @DisplayName("과거 나노초 요청은 기존 예약이 없으면 MySQL에 행을 남기지 않고 400으로 거부한다")
    void rejectsLegacySubMicrosecondTimeWithoutMysqlReservation() throws Exception {
        String idempotencyKey = "0e85e771-1261-4630-a1f3-9b46573aa300";
        String targetPath = "/room/jkmn-pqrs-tuvw";
        Instant historicalExpiresAt = Instant.parse("2040-06-02T13:00:00.123456789Z");

        mockMvc.perform(post("/api/v1/links")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer test-management-token-that-is-long-enough"
                        )
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "ROUND",
                                  "targetPath": "%s",
                                  "purpose": "MEETING_ENTRY",
                                  "expiresAt": "%s"
                                }
                                """.formatted(targetPath, historicalExpiresAt)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM smart_links WHERE code_hash = ?",
                Long.class,
                linkCodePort.issue(idempotencyKey).codeHash()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM link_creation_requests WHERE idempotency_key_hash = ?",
                Long.class,
                linkCodePort.hashIdempotencyKey(idempotencyKey)
        )).isZero();
    }

    @Test
    @DisplayName("저장된 unknown과 공백 enum의 생성 재생은 raw 값 없이 충돌로 거부한다")
    void rejectsUnsafeStoredReplayEnumsWithoutHydrationOrExposure() throws Exception {
        assertUnsafeStoredReplayIsRejected(
                "c1478a51-2c84-451f-8291-4f3fb563ac20",
                "83aa4b8b-49ae-487c-9250-a193828ff8d1",
                "BATON_LEGACY",
                CANONICAL_BATON_TARGET,
                "NAVIGATION"
        );
        assertUnsafeStoredReplayIsRejected(
                "de283961-52f7-4612-a6ef-d94ba3b36f39",
                "66c06fb7-69c6-4bd9-a396-a49cf8450a65",
                "BATON",
                CANONICAL_BATON_TARGET,
                "NAVIGATION "
        );
    }

    @Test
    @DisplayName("정상 생성 재생은 현재 폐기 시각을 보존해 반환한다")
    void replaysCurrentRevokedStateThroughRawProjection() {
        CreateLinkCommand command = new CreateLinkCommand(
                new CreationIdempotencyKey("bbbf78dd-3a8d-4624-b30d-e20983138d2a"),
                TargetSystem.ROUND,
                "/room/abcd-efgh-jkmn",
                LinkPurpose.MEETING_ENTRY,
                null,
                null
        );

        CreatedLinkResult first = smartLinkUseCase.createLink(command);
        var revoked = smartLinkUseCase.revokeLink(first.link().id());
        CreatedLinkResult replay = smartLinkUseCase.createLink(command);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.link().id()).isEqualTo(first.link().id());
        assertThat(replay.shortUrl()).isEqualTo(first.shortUrl());
        assertThat(replay.link().revokedAt()).isEqualTo(revoked.revokedAt());
    }

    @Test
    @DisplayName("저장된 비허용 target은 GET과 HEAD에서 존재를 숨기고 리다이렉트하지 않는다")
    void hidesStoredTargetPolicyViolationFromGetAndHead() throws Exception {
        String rawCode = "A".repeat(22);
        insertStoredLink(
                "ae1e4899-d73f-42f6-82cf-43cc1723939f",
                rawCode,
                "BATON",
                "/teams/legacy-target",
                "NAVIGATION"
        );

        assertStoredTargetIsHidden(rawCode);
    }

    @Test
    @DisplayName("저장된 알 수 없는 enum은 GET과 HEAD에서 존재를 숨기고 리다이렉트하지 않는다")
    void hidesUnknownStoredEnumFromGetAndHead() throws Exception {
        String rawCode = "B".repeat(22);
        insertStoredLink(
                "70f147f2-b02a-4a63-bc27-bf60e44db591",
                rawCode,
                "LEGACY",
                CANONICAL_BATON_TARGET,
                "NAVIGATION"
        );

        assertStoredTargetIsHidden(rawCode);
    }

    @Test
    @DisplayName("동시에 같은 생성 요청을 보내도 MySQL에는 링크와 예약이 한 건만 남는다")
    void serializesConcurrentCreation() throws Exception {
        CreateLinkCommand command = new CreateLinkCommand(
                new CreationIdempotencyKey(IDEMPOTENCY_KEY),
                TargetSystem.ROUND,
                "/room/abcd-efgh-jkmn",
                LinkPurpose.MEETING_ENTRY,
                null,
                null
        );
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        controllableReservationPort.arm(CONCURRENCY, false);

        try {
            List<Future<CreatedLinkResult>> futures = submitConcurrentCreations(
                    executor,
                    command
            );

            controllableReservationPort.awaitAllEntered();
            controllableReservationPort.awaitFirstOwnerReserved();
            awaitReservationInsertWaiters(CONCURRENCY - 1);
            assertThat(futures).allMatch(future -> !future.isDone());

            controllableReservationPort.releaseFirstOwner();
            List<CreatedLinkResult> results = successfulResults(futures);

            CreatedLinkResult first = results.getFirst();
            assertThat(results)
                    .extracting(result -> result.link().id())
                    .containsOnly(first.link().id());
            assertThat(results)
                    .extracting(CreatedLinkResult::shortUrl)
                    .containsOnly(first.shortUrl());
            assertThat(results)
                    .filteredOn(result -> !result.replayed())
                    .hasSize(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM smart_links WHERE target_path = ?",
                    Long.class,
                    command.targetPath()
            )).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    """
                            SELECT COUNT(*)
                            FROM link_creation_requests request
                            JOIN smart_links link ON link.id = request.link_id
                            WHERE link.target_path = ?
                            """,
                    Long.class,
                    command.targetPath()
            )).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT code_hash FROM smart_links WHERE target_path = ?",
                    String.class,
                    command.targetPath()
            ))
                    .matches("^[0-9a-f]{64}$")
                    .isNotEqualTo(rawCode(first));
            assertThat(jdbcTemplate.queryForObject(
                    """
                            SELECT request.idempotency_key_hash
                            FROM link_creation_requests request
                            JOIN smart_links link ON link.id = request.link_id
                            WHERE link.target_path = ?
                            """,
                    String.class,
                    command.targetPath()
            ))
                    .matches("^[0-9a-f]{64}$")
                    .isNotEqualTo(IDEMPOTENCY_KEY);
            assertThatThrownBy(() -> smartLinkUseCase.createLink(new CreateLinkCommand(
                    new CreationIdempotencyKey(IDEMPOTENCY_KEY),
                    TargetSystem.ROUND,
                    "/room/qrst-uvwx-yz23",
                    LinkPurpose.MEETING_ENTRY,
                    null,
                    null
            )))
                    .isInstanceOf(IdempotencyKeyConflictException.class);
        } finally {
            controllableReservationPort.releaseFirstOwner();
            controllableReservationPort.reset();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("서로 다른 공개 origin의 replica가 경쟁해도 저장된 승자 short URL로 수렴한다")
    void convergesOnStoredPublicOriginAcrossConcurrentReplicas() throws Exception {
        String idempotencyKey = "f14af1a6-9d56-4a41-8f47-c05f7c8898a1";
        CreateLinkCommand command = new CreateLinkCommand(
                new CreationIdempotencyKey(idempotencyKey),
                TargetSystem.ROUND,
                "/room/wxyz-2345-6789",
                LinkPurpose.MEETING_ENTRY,
                null,
                null
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        controllableReservationPort.arm(2, false);

        try {
            Future<CreatedLinkResult> firstFuture = executor.submit(() ->
                    controllablePublicLinkOriginPort.withOrigin(
                            "https://go-a.example",
                            () -> smartLinkUseCase.createLink(command)
                    ));
            Future<CreatedLinkResult> secondFuture = executor.submit(() ->
                    controllablePublicLinkOriginPort.withOrigin(
                            "https://go-b.example",
                            () -> smartLinkUseCase.createLink(command)
                    ));

            controllableReservationPort.awaitAllEntered();
            controllableReservationPort.awaitFirstOwnerReserved();
            awaitReservationInsertWaiters(1);
            controllableReservationPort.releaseFirstOwner();

            CreatedLinkResult first = firstFuture.get(20, TimeUnit.SECONDS);
            CreatedLinkResult second = secondFuture.get(20, TimeUnit.SECONDS);
            String storedOrigin = jdbcTemplate.queryForObject(
                    """
                            SELECT public_origin
                            FROM link_creation_requests
                            WHERE idempotency_key_hash = ?
                            """,
                    String.class,
                    linkCodePort.hashIdempotencyKey(idempotencyKey)
            );
            URI expectedShortUrl = URI.create(
                    storedOrigin + first.shortUrl().getRawPath()
            );

            assertThat(storedOrigin)
                    .isIn("https://go-a.example", "https://go-b.example");
            assertThat(first.link().id()).isEqualTo(second.link().id());
            assertThat(first.shortUrl()).isEqualTo(expectedShortUrl);
            assertThat(second.shortUrl()).isEqualTo(expectedShortUrl);
            assertThat(List.of(first, second))
                    .filteredOn(result -> !result.replayed())
                    .hasSize(1);
        } finally {
            controllableReservationPort.releaseFirstOwner();
            controllableReservationPort.reset();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("첫 생성 transaction이 rollback되면 대기 요청 하나가 승계하고 나머지는 재시도할 수 있다")
    void transfersOwnershipAfterWinnerRollback() throws Exception {
        CreateLinkCommand command = new CreateLinkCommand(
                new CreationIdempotencyKey(ROLLBACK_IDEMPOTENCY_KEY),
                TargetSystem.ROUND,
                "/room/mnpq-rstu-vwxy",
                LinkPurpose.MEETING_ENTRY,
                null,
                null
        );
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        controllableReservationPort.arm(CONCURRENCY, true);

        try {
            List<Future<CreatedLinkResult>> futures = submitConcurrentCreations(
                    executor,
                    command
            );

            controllableReservationPort.awaitAllEntered();
            controllableReservationPort.awaitFirstOwnerReserved();
            awaitReservationInsertWaiters(CONCURRENCY - 1);
            assertThat(futures).allMatch(future -> !future.isDone());

            controllableReservationPort.releaseFirstOwner();
            List<CreatedLinkResult> results = new ArrayList<>();
            int rollbackFailures = 0;
            int retryableFailures = 0;
            for (Future<CreatedLinkResult> future : futures) {
                try {
                    results.add(future.get(20, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    if (exception.getCause() instanceof ForcedReservationRollbackException) {
                        rollbackFailures++;
                    } else {
                        assertThat(exception.getCause())
                                .isInstanceOf(CannotAcquireLockException.class);
                        retryableFailures++;
                    }
                }
            }

            assertThat(rollbackFailures).isEqualTo(1);
            assertThat(retryableFailures).isBetween(1, CONCURRENCY - 2);
            assertThat(results).isNotEmpty();
            assertThat(results.size() + rollbackFailures + retryableFailures)
                    .isEqualTo(CONCURRENCY);
            CreatedLinkResult first = results.getFirst();
            assertThat(results)
                    .extracting(result -> result.link().id())
                    .containsOnly(first.link().id());
            assertThat(results)
                    .extracting(CreatedLinkResult::shortUrl)
                    .containsOnly(first.shortUrl());
            assertThat(results)
                    .filteredOn(result -> !result.replayed())
                    .hasSize(1);

            CreatedLinkResult replay = smartLinkUseCase.createLink(command);
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.link().id()).isEqualTo(first.link().id());
            assertThat(replay.shortUrl()).isEqualTo(first.shortUrl());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM smart_links WHERE target_path = ?",
                    Long.class,
                    command.targetPath()
            )).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    """
                            SELECT COUNT(*)
                            FROM link_creation_requests request
                            JOIN smart_links link ON link.id = request.link_id
                            WHERE link.target_path = ?
                            """,
                    Long.class,
                    command.targetPath()
            )).isEqualTo(1L);
        } finally {
            controllableReservationPort.releaseFirstOwner();
            controllableReservationPort.reset();
            executor.shutdownNow();
        }
    }

    private void insertStoredLink(
            String linkId,
            String rawCode,
            String targetSystem,
            String targetPath,
            String purpose
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO smart_links (
                            id,
                            code_hash,
                            target_system,
                            target_path,
                            purpose,
                            created_at,
                            version
                        ) VALUES (UUID_TO_BIN(?), ?, ?, ?, ?, UTC_TIMESTAMP(6), 0)
                        """,
                linkId,
                linkCodePort.hash(rawCode),
                targetSystem,
                targetPath,
                purpose
        );
    }

    private void assertUnsafeStoredReplayIsRejected(
            String linkId,
            String idempotencyKey,
            String rawTargetSystem,
            String rawTargetPath,
            String rawPurpose
    ) throws Exception {
        String codeHash = linkCodePort.issue(idempotencyKey).codeHash();
        jdbcTemplate.update(
                """
                        INSERT INTO smart_links (
                            id,
                            code_hash,
                            target_system,
                            target_path,
                            purpose,
                            created_at,
                            version
                        ) VALUES (UUID_TO_BIN(?), ?, ?, ?, ?, UTC_TIMESTAMP(6), 0)
                        """,
                linkId,
                codeHash,
                rawTargetSystem,
                rawTargetPath,
                rawPurpose
        );
        jdbcTemplate.update(
                """
                        INSERT INTO link_creation_requests (
                            idempotency_key_hash,
                            link_id,
                            created_at
                        ) VALUES (?, UUID_TO_BIN(?), UTC_TIMESTAMP(6))
                        """,
                linkCodePort.hashIdempotencyKey(idempotencyKey),
                linkId
        );

        String responseBody = mockMvc.perform(post("/api/v1/links")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer test-management-token-that-is-long-enough"
                        )
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "BATON",
                                  "targetPath": "%s",
                                  "purpose": "NAVIGATION"
                                }
                                """.formatted(CANONICAL_BATON_TARGET)))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.message")
                        .value("같은 Idempotency-Key를 다른 링크 생성 요청에 사용할 수 없습니다"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(responseBody)
                .doesNotContain(rawTargetSystem, rawTargetPath, rawPurpose, codeHash);
    }

    private StoredAbsoluteTimes storedAbsoluteTimes(UUID linkId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT link.not_before,
                               link.expires_at,
                               link.revoked_at,
                               link.created_at,
                               creation_request.created_at AS request_created_at
                        FROM smart_links link
                        JOIN link_creation_requests creation_request
                          ON creation_request.link_id = link.id
                        WHERE link.id = UUID_TO_BIN(?)
                        """,
                (resultSet, rowNumber) -> new StoredAbsoluteTimes(
                        instant(resultSet, "not_before"),
                        instant(resultSet, "expires_at"),
                        instant(resultSet, "revoked_at"),
                        instant(resultSet, "created_at"),
                        instant(resultSet, "request_created_at")
                ),
                linkId.toString()
        );
    }

    private List<SchemaObject> schemaObjects(
            String informationSchemaTable,
            String objectNameColumn
    ) {
        return jdbcTemplate.query(
                """
                        SELECT DISTINCT table_name, %s AS object_name
                        FROM %s
                        WHERE table_schema = DATABASE()
                          AND table_name IN ('smart_links', 'link_creation_requests')
                        """.formatted(objectNameColumn, informationSchemaTable),
                (resultSet, rowNumber) -> new SchemaObject(
                        resultSet.getString("table_name"),
                        resultSet.getString("object_name")
                )
        );
    }

    private Instant instant(ResultSet resultSet, String columnName) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(columnName, utcCalendar());
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Calendar utcCalendar() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private void assertStoredTargetIsHidden(String rawCode) throws Exception {
        String requestId = "stored-target-policy-test";

        mockMvc.perform(get("/l/{code}", rawCode)
                        .header("X-Request-Id", requestId))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Request-Id", requestId))
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("링크를 찾을 수 없습니다"))
                .andExpect(jsonPath("$.requestId").value(requestId));

        mockMvc.perform(head("/l/{code}", rawCode)
                        .header("X-Request-Id", requestId))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Request-Id", requestId))
                .andExpect(content().string(""));
    }

    private List<Future<CreatedLinkResult>> submitConcurrentCreations(
            ExecutorService executor,
            CreateLinkCommand command
    ) {
        List<Future<CreatedLinkResult>> futures = new ArrayList<>();
        for (int index = 0; index < CONCURRENCY; index++) {
            futures.add(executor.submit(() -> smartLinkUseCase.createLink(command)));
        }
        return futures;
    }

    private List<CreatedLinkResult> successfulResults(
            List<Future<CreatedLinkResult>> futures
    ) throws Exception {
        List<CreatedLinkResult> results = new ArrayList<>();
        for (Future<CreatedLinkResult> future : futures) {
            results.add(future.get(20, TimeUnit.SECONDS));
        }
        return results;
    }

    private String rawCode(CreatedLinkResult result) {
        String path = result.shortUrl().getRawPath();
        return path.substring("/l/".length());
    }

    private void awaitReservationInsertWaiters(int expectedWaiters)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long observedWaiters = 0;
        while (System.nanoTime() < deadline) {
            observedWaiters = jdbcTemplate.queryForObject(
                    """
                            SELECT COUNT(*)
                            FROM information_schema.PROCESSLIST
                            WHERE DB = DATABASE()
                              AND COMMAND = 'Query'
                              AND INFO LIKE 'INSERT IGNORE INTO link_creation_requests%'
                            """,
                    Long.class
            );
            if (observedWaiters >= expectedWaiters) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError(
                "예약 INSERT lock waiter가 모두 관찰되지 않았습니다: expected="
                        + expectedWaiters
                        + ", observed="
                        + observedWaiters
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ConcurrencyTestConfiguration {

        @Bean
        @Primary
        Clock farFutureClock() {
            return Clock.fixed(FAR_FUTURE_NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        ControllableReservationPort controllableReservationPort(
                @Qualifier("linkCreationReservationPersistenceAdapter")
                LinkCreationReservationPort delegate
        ) {
            return new ControllableReservationPort(delegate);
        }

        @Bean
        @Primary
        ControllablePublicLinkOriginPort controllablePublicLinkOriginPort() {
            return new ControllablePublicLinkOriginPort();
        }
    }

    static final class ControllablePublicLinkOriginPort implements PublicLinkOriginPort {

        private static final PublicLinkOrigin DEFAULT_ORIGIN =
                new PublicLinkOrigin(URI.create("https://go.example"));

        private final ThreadLocal<PublicLinkOrigin> currentOrigin = new ThreadLocal<>();

        @Override
        public PublicLinkOrigin current() {
            PublicLinkOrigin configuredOrigin = currentOrigin.get();
            return configuredOrigin == null ? DEFAULT_ORIGIN : configuredOrigin;
        }

        <T> T withOrigin(String origin, Callable<T> action) throws Exception {
            currentOrigin.set(new PublicLinkOrigin(URI.create(origin)));
            try {
                return action.call();
            } finally {
                currentOrigin.remove();
            }
        }
    }

    static final class ControllableReservationPort implements LinkCreationReservationPort {

        private final LinkCreationReservationPort delegate;
        private final AtomicBoolean firstOwnerHandled = new AtomicBoolean();

        private volatile CountDownLatch allEntered = new CountDownLatch(0);
        private volatile CountDownLatch firstOwnerReserved = new CountDownLatch(0);
        private volatile CountDownLatch releaseFirstOwner = new CountDownLatch(0);
        private volatile boolean failFirstOwner;

        private ControllableReservationPort(LinkCreationReservationPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.util.Optional<Reservation> find(String idempotencyKeyHash) {
            return delegate.find(idempotencyKeyHash);
        }

        void arm(int expectedRequests, boolean failFirstOwner) {
            this.allEntered = new CountDownLatch(expectedRequests);
            this.firstOwnerReserved = new CountDownLatch(1);
            this.releaseFirstOwner = new CountDownLatch(1);
            this.failFirstOwner = failFirstOwner;
            this.firstOwnerHandled.set(false);
        }

        @Override
        public Reservation reserve(
                String idempotencyKeyHash,
                UUID proposedLinkId,
                String publicOrigin,
                Instant createdAt
        ) {
            allEntered.countDown();
            Reservation reservation = delegate.reserve(
                    idempotencyKeyHash,
                    proposedLinkId,
                    publicOrigin,
                    createdAt
            );
            if (reservation.owner() && firstOwnerHandled.compareAndSet(false, true)) {
                firstOwnerReserved.countDown();
                await(releaseFirstOwner, "첫 승자 transaction 해제를 기다리지 못했습니다");
                if (failFirstOwner) {
                    throw new ForcedReservationRollbackException();
                }
            }
            return reservation;
        }

        void awaitAllEntered() {
            await(allEntered, "모든 동시 생성 요청이 reservation port에 진입하지 못했습니다");
        }

        void awaitFirstOwnerReserved() {
            await(firstOwnerReserved, "첫 생성 승자가 reservation을 확보하지 못했습니다");
        }

        void releaseFirstOwner() {
            releaseFirstOwner.countDown();
        }

        void reset() {
            allEntered = new CountDownLatch(0);
            firstOwnerReserved = new CountDownLatch(0);
            releaseFirstOwner = new CountDownLatch(0);
            failFirstOwner = false;
            firstOwnerHandled.set(false);
        }

        private void await(CountDownLatch latch, String message) {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(message);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(message, exception);
            }
        }
    }

    static final class ForcedReservationRollbackException extends RuntimeException {
    }

    private record StoredAbsoluteTimes(
            Instant notBefore,
            Instant expiresAt,
            Instant revokedAt,
            Instant createdAt,
            Instant requestCreatedAt
    ) {
    }

    private record RawBoundaryTimes(String notBefore, String expiresAt) {
    }

    private record TimeColumn(
            String tableName,
            String columnName,
            String dataType,
            int datetimePrecision,
            String nullable
    ) {
    }

    private record SchemaObject(String tableName, String objectName) {
    }
}
