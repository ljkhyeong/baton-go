package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.personal.batongo.application.link.CreationIdempotencyKey;
import com.personal.batongo.application.link.PublicLinkOrigin;
import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.error.PublicLinkOriginReplayUnavailableException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreatedLinkResult;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.LinkResult;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongo.application.link.port.out.LinkCreationReservationPort;
import com.personal.batongo.application.link.port.out.PublicLinkOriginPort;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.SmartLink;
import com.personal.batongo.domain.link.TargetSystem;
import com.personal.batongo.domain.link.TrustedTargetPolicy;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("mysql")
@Testcontainers
@AutoConfigureMockMvc
@Import(LinkCreationIdempotencyIntegrationTest.ConcurrencyTestConfiguration.class)
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.audiences=baton-go",
        "baton-go.link-code.secret=test-link-code-secret-that-is-separate-and-long-enough",
        "baton-go.public-base-url=https://go.example",
        "baton-go.targets.baton-base-url=https://baton.example",
        "baton-go.targets.round-base-url=https://baton.example"
})
class LinkCreationIdempotencyIntegrationTest {

    private static final Instant MINIMUM_SUPPORTED_TIME =
            Instant.parse("1582-10-15T00:00:00Z");
    private static final Instant MAXIMUM_SUPPORTED_TIME =
            Instant.parse("9999-12-31T23:59:59.999999Z");

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
    private static final String JWT_KEY_ID = "management-integration-test";
    private static final KeyPair JWT_KEY_PAIR = jwtKeyPair();
    private static final HttpServer JWT_SERVER = startJwtServer();
    private static final String JWT_ISSUER =
            "http://127.0.0.1:" + JWT_SERVER.getAddress().getPort() + "/issuer";

    @Container
    @ServiceConnection(name = "mysql")
    static final MySQLContainer MYSQL = new MySQLContainer(MySqlTestImage.NAME);

    @DynamicPropertySource
    static void managementJwtProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> JWT_ISSUER
        );
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> JWT_ISSUER + "/jwks"
        );
    }

    @AfterAll
    static void stopJwtServer() {
        JWT_SERVER.stop(0);
    }

    @Autowired
    private SmartLinkUseCase smartLinkUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LinkCodePort linkCodePort;

    @Autowired
    private ControllableReservationPort controllableReservationPort;

    @Autowired
    private ControllablePublicLinkOriginPort controllablePublicLinkOriginPort;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Flyway와 JPA는 2040년 링크의 생성 재생 폐기 시각을 마이크로초까지 보존한다")
    void persistsCreationReplayAndRevocationAfterTimestampLimit() {
        CreateLinkCommand command = new CreateLinkCommand(
                CreationIdempotencyKey.parseRequest(FAR_FUTURE_IDEMPOTENCY_KEY),
                TargetSystem.ROUND,
                "/room/wxyz-2345-6789",
                LinkPurpose.MEETING_ENTRY,
                FAR_FUTURE_NOT_BEFORE,
                FAR_FUTURE_EXPIRES_AT
        );

        CreatedLinkResult created = smartLinkUseCase.createLink(command);
        CreatedLinkResult replayed = smartLinkUseCase.createLink(command);

        assertThat(created.link().createdAt()).isEqualTo(FAR_FUTURE_NOW);
        assertThat(created.link().notBefore()).isEqualTo(FAR_FUTURE_NOT_BEFORE);
        assertThat(created.link().expiresAt()).isEqualTo(FAR_FUTURE_EXPIRES_AT);
        assertThat(replayed.link().createdAt()).isEqualTo(FAR_FUTURE_NOW);
        assertThat(replayed.link().notBefore()).isEqualTo(FAR_FUTURE_NOT_BEFORE);
        assertThat(replayed.link().expiresAt()).isEqualTo(FAR_FUTURE_EXPIRES_AT);

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
    @DisplayName("API 지원 시각의 양쪽 경계는 생성 응답과 MySQL raw 날짜에서 그대로 보존된다")
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
                MINIMUM_SUPPORTED_TIME,
                MAXIMUM_SUPPORTED_TIME
        );

        var createdResponse = mockMvc.perform(post("/api/v1/links")
                        .with(linkCreateJwt())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.notBefore")
                        .value(MINIMUM_SUPPORTED_TIME.toString()))
                .andExpect(jsonPath("$.expiresAt")
                        .value(MAXIMUM_SUPPORTED_TIME.toString()))
                .andReturn()
                .getResponse();
        String location = createdResponse.getHeader(HttpHeaders.LOCATION);
        assertThat(location).isNotNull();
        UUID linkId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

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
    @DisplayName("실제 Spring 조립은 서명과 issuer와 audience를 검증한 관리 JWT만 허용한다")
    void assemblesManagementJwtAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/operations/link-target-contract-v1/inventory"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"baton-go-management\""
                ))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code")
                        .value("MANAGEMENT_AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        mockMvc.perform(get("/api/v1/operations/link-target-contract-v1/inventory")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + managementJwt(JWT_ISSUER, "baton-go")
                        ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/operations/link-target-contract-v1/inventory")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + managementJwt(JWT_ISSUER, "another-service")
                        ))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("MANAGEMENT_AUTHENTICATION_REQUIRED"));

        mockMvc.perform(get("/api/v1/operations/link-target-contract-v1/inventory")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + managementJwt(
                                        JWT_ISSUER + "/another-issuer",
                                        "baton-go"
                                )
                        ))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("MANAGEMENT_AUTHENTICATION_REQUIRED"));
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
                        .with(linkCreateJwt())
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
    @DisplayName("공개 origin 증거가 없는 기존 MySQL 예약은 현재 설정으로 추정하지 않는다")
    void rejectsExistingMysqlReservationWithoutPublicOrigin() {
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

        assertThatThrownBy(() -> smartLinkUseCase.createLink(new CreateLinkCommand(
                CreationIdempotencyKey.parseRequest(idempotencyKey),
                TargetSystem.ROUND,
                targetPath,
                LinkPurpose.MEETING_ENTRY,
                null,
                null
        )))
                .isInstanceOf(PublicLinkOriginReplayUnavailableException.class);
    }

    @Test
    @DisplayName("과거 나노초 요청은 기존 MySQL 예약의 마이크로초 payload와 일치할 때 재생한다")
    void replaysLegacySubMicrosecondTimeFromExistingMysqlReservation() throws Exception {
        String idempotencyKey = "61a78df8-4859-4e66-8ad9-57c3a29bd2d2";
        String targetPath = "/room/abcd-2345-efgh";
        Instant historicalExpiresAt = Instant.parse("2040-06-02T13:00:00.123456789Z");
        Instant storedExpiresAt = Instant.parse("2040-06-02T13:00:00.123456Z");
        CreatedLinkResult created = smartLinkUseCase.createLink(new CreateLinkCommand(
                CreationIdempotencyKey.parseRequest(idempotencyKey),
                TargetSystem.ROUND,
                targetPath,
                LinkPurpose.MEETING_ENTRY,
                null,
                storedExpiresAt
        ));

        mockMvc.perform(post("/api/v1/links")
                        .with(linkCreateJwt())
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
    @DisplayName("저장된 비허용 target과 알 수 없는 enum은 GET과 HEAD에서 각각 숨긴다")
    void hidesUnsafeStoredTargetsFromGetAndHead() throws Exception {
        String invalidTargetCode = "A".repeat(22);
        insertStoredLink(
                "ae1e4899-d73f-42f6-82cf-43cc1723939f",
                invalidTargetCode,
                "BATON",
                "/teams/legacy-target",
                "NAVIGATION"
        );

        mockMvc.perform(get("/l/{code}", invalidTargetCode))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"))
                .andExpect(content().string(not(containsString("/teams/legacy-target"))));

        String unknownEnumCode = "B".repeat(22);
        insertStoredLink(
                "70f147f2-b02a-4a63-bc27-bf60e44db591",
                unknownEnumCode,
                "LEGACY",
                CANONICAL_BATON_TARGET,
                "NAVIGATION"
        );

        mockMvc.perform(head("/l/{code}", unknownEnumCode))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("동시에 같은 생성 요청을 보내도 MySQL에는 링크와 예약이 한 건만 남는다")
    void serializesConcurrentCreation() throws Exception {
        CreateLinkCommand command = new CreateLinkCommand(
                CreationIdempotencyKey.parseRequest(IDEMPOTENCY_KEY),
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
            List<CreatedLinkResult> results = new ArrayList<>();
            for (Future<CreatedLinkResult> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }

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
                    .matches("^[0-9a-f]{64}$");
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
                    .matches("^[0-9a-f]{64}$");
            assertThatThrownBy(() -> smartLinkUseCase.createLink(new CreateLinkCommand(
                    CreationIdempotencyKey.parseRequest(IDEMPOTENCY_KEY),
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
                CreationIdempotencyKey.parseRequest(idempotencyKey),
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
                CreationIdempotencyKey.parseRequest(ROLLBACK_IDEMPOTENCY_KEY),
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
                                .isInstanceOf(TransientDataAccessException.class);
                        retryableFailures++;
                    }
                }
            }

            assertThat(rollbackFailures).isEqualTo(1);
            assertThat(retryableFailures).isBetween(0, CONCURRENCY - 2);
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
                        .with(linkCreateJwt())
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

    private Instant instant(ResultSet resultSet, String columnName) throws SQLException {
        LocalDateTime value = resultSet.getObject(columnName, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private RequestPostProcessor linkCreateJwt() {
        return jwt().authorities(new SimpleGrantedAuthority(
                "SCOPE_baton-go.links.create"
        ));
    }

    private RequestPostProcessor targetContractOperateJwt() {
        return jwt().authorities(new SimpleGrantedAuthority(
                "SCOPE_baton-go.target-contract.operate"
        ));
    }

    private String managementJwt(String issuer, String audience) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("baton-integration-test")
                .audience(List.of(audience))
                .issuedAt(now.minusSeconds(5))
                .expiresAt(now.plusSeconds(60))
                .claim("scope", "baton-go.target-contract.operate")
                .build();
        JwtEncoder encoder = NimbusJwtEncoder.withKeyPair(
                        (RSAPublicKey) JWT_KEY_PAIR.getPublic(),
                        (RSAPrivateKey) JWT_KEY_PAIR.getPrivate()
                )
                .jwkPostProcessor(key -> key.keyID(JWT_KEY_ID))
                .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private static KeyPair jwtKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("RSA 테스트 키를 생성할 수 없습니다", exception);
        }
    }

    private static HttpServer startJwtServer() {
        try {
            RSAKey publicKey = new RSAKey.Builder(
                    (RSAPublicKey) JWT_KEY_PAIR.getPublic()
            ).keyID(JWT_KEY_ID).build();
            byte[] jwkSet = new JWKSet(publicKey)
                    .toPublicJWKSet()
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            HttpServer server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0),
                    0
            );
            server.createContext("/issuer/jwks", exchange -> {
                exchange.getResponseHeaders().set(
                        HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_JSON_VALUE
                );
                exchange.sendResponseHeaders(200, jwkSet.length);
                try (var response = exchange.getResponseBody()) {
                    response.write(jwkSet);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("관리 JWT 테스트 서버를 시작할 수 없습니다", exception);
        }
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

    private void awaitReservationInsertWaiters(int expectedWaiters) {
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(25, TimeUnit.MILLISECONDS)
                .until(() -> jdbcTemplate.queryForObject(
                        """
                                SELECT COUNT(*)
                                FROM information_schema.PROCESSLIST
                                WHERE DB = DATABASE()
                                  AND COMMAND = 'Query'
                                  AND INFO LIKE 'INSERT IGNORE INTO link_creation_requests%'
                                """,
                        Long.class
                ) >= expectedWaiters);
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

}
