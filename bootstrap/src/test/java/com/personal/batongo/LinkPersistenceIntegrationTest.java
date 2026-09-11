package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import com.personal.batongo.application.link.CreationIdempotencyKey;
import com.personal.batongo.application.link.error.PublicLinkOriginReplayUnavailableException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreatedLinkResult;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.LinkResult;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("mysql")
@Testcontainers
@AutoConfigureMockMvc
@Import(LinkPersistenceIntegrationTest.FixedClockTestConfiguration.class)
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://identity.example/jwks",
        "spring.security.oauth2.resourceserver.jwt.audiences=baton-go",
        "baton-go.link-code.secret=test-link-code-secret-that-is-separate-and-long-enough",
        "baton-go.public-base-url=https://go.example",
        "baton-go.targets.baton-base-url=https://baton.example",
        "baton-go.targets.round-base-url=https://baton.example"
})
class LinkPersistenceIntegrationTest {

    private static final Instant MINIMUM_SUPPORTED_TIME =
            Instant.parse("1582-10-15T00:00:00Z");
    private static final Instant MAXIMUM_SUPPORTED_TIME =
            Instant.parse("9999-12-31T23:59:59.999999Z");

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
    @ServiceConnection(name = "mysql")
    static final MySQLContainer MYSQL = new MySQLContainer(MySqlTestImage.NAME)
            .withUrlParam("connectTimeout", "3000")
            .withUrlParam("socketTimeout", "30000");

    @Autowired
    private SmartLinkUseCase smartLinkUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LinkCodePort linkCodePort;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("일괄 조회는 요청한 MySQL 링크만 읽고 폐기 상태와 누락 ID를 구분한다")
    void getsStoredLinksInBatch() throws Exception {
        var active = smartLinkUseCase.createLink(new CreateLinkCommand(
                CreationIdempotencyKey.parseRequest(UUID.randomUUID().toString()),
                TargetSystem.ROUND, "/room/abcd-efgh-jkmn", LinkPurpose.MEETING_ENTRY, null, null
        )).link();
        var revoked = smartLinkUseCase.createLink(new CreateLinkCommand(
                CreationIdempotencyKey.parseRequest(UUID.randomUUID().toString()),
                TargetSystem.BATON, CANONICAL_BATON_TARGET, LinkPurpose.NAVIGATION, null, null
        )).link();
        smartLinkUseCase.revokeLink(revoked.id());
        UUID missingId = UUID.randomUUID();
        UUID hiddenId = UUID.randomUUID();
        insertStoredLink(hiddenId.toString(), UUID.randomUUID().toString().substring(0, 22),
                "UNKNOWN", "/private-target", "UNKNOWN");

        mockMvc.perform(get("/api/v1/links/batch")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_baton-go.links.read")))
                        .param("linkIds", revoked.id() + "," + missingId + "," + hiddenId + ","
                                + active.id() + "," + revoked.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(revoked.id().toString()))
                .andExpect(jsonPath("$.items[0].status").value("REVOKED"))
                .andExpect(jsonPath("$.items[1].id").value(active.id().toString()))
                .andExpect(jsonPath("$.items[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$.notFoundIds[0]").value(missingId.toString()))
                .andExpect(jsonPath("$.notFoundIds[1]").value(hiddenId.toString()))
                .andExpect(jsonPath("$.notFoundIds.length()").value(2))
                .andExpect(jsonPath("$.evaluatedAt").value(FAR_FUTURE_NOW.toString()))
                .andExpect(content().string(not(containsString("/private-target"))));
    }

    @Test
    @DisplayName("일괄 조회의 빈 ID와 100개 초과 입력은 HTTP 400으로 거부한다")
    void rejectsInvalidBatchInputOverHttp() throws Exception {
        String id = UUID.randomUUID().toString();
        for (String ids : List.of("", id + ",," + id, String.join(",", Collections.nCopies(101, id)))) {
            mockMvc.perform(get("/api/v1/links/batch").param("linkIds", ids)
                            .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_baton-go.links.read"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    @DisplayName("Flyway와 JPA는 2040년 링크의 생성·재시도·폐기 시각을 마이크로초까지 보존한다")
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

        LinkResult revoked = smartLinkUseCase.revokeLink(created.link().id()).link();
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
    @DisplayName("API가 지원하는 최소·최대 시각은 생성 응답과 MySQL 원문 날짜에 그대로 보존된다")
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
    @DisplayName("과거 대문자 UUID 요청은 기존 MySQL 예약과 일치하면 행을 늘리지 않고 기존 결과를 반환한다")
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
    @DisplayName("최초 공개 출처를 확인할 수 없는 기존 예약은 현재 설정으로 채우지 않는다")
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
    @DisplayName("과거 나노초 요청은 기존 MySQL 예약의 마이크로초 요청 내용과 일치하면 기존 결과를 반환한다")
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
    @DisplayName("알 수 없거나 공백인 열거형의 재시도는 원문을 숨기고 충돌로 거부한다")
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
    @DisplayName("허용되지 않은 저장 대상과 알 수 없는 열거형은 GET과 HEAD에서 숨긴다")
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
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        // HEAD의 빈 본문은 실제 HTTP 서버를 사용하는 PublicErrorResponseIntegrationTest에서 확인한다.
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

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockTestConfiguration {

        @Bean
        @Primary
        Clock farFutureClock() {
            return Clock.fixed(FAR_FUTURE_NOW, ZoneOffset.UTC);
        }
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
