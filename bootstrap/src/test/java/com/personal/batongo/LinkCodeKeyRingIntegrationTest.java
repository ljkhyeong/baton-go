package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.adapter.out.external.link.LinkCodeProperties;
import com.personal.batongo.adapter.out.external.link.SecureLinkCodeAdapter;
import com.personal.batongo.application.link.CreationIdempotencyKey;
import com.personal.batongo.application.link.LinkCodeKeyGuard;
import com.personal.batongo.application.link.SmartLinkService;
import com.personal.batongo.application.link.error.LinkCodeKeyBindingException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.out.LinkCodeKeyGuardPort;
import com.personal.batongo.application.link.port.out.LinkCreationReservationPort;
import com.personal.batongo.application.link.port.out.PublicLinkOriginPort;
import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.TargetUrlPort;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://identity.example/jwks",
        "spring.security.oauth2.resourceserver.jwt.audiences=baton-go",
        "baton-go.link-code.secret=test-legacy-key-with-at-least-thirty-two-characters",
        "baton-go.link-code.keys.k202609=test-current-key-with-at-least-thirty-two-characters",
        "baton-go.public-base-url=https://go.example",
        "baton-go.targets.baton-base-url=https://baton.example",
        "baton-go.targets.round-base-url=https://baton.example"
})
class LinkCodeKeyRingIntegrationTest {

    private static final String LEGACY = "test-legacy-key-with-at-least-thirty-two-characters";
    private static final String CURRENT = "test-current-key-with-at-least-thirty-two-characters";

    @Container
    @ServiceConnection(name = "mysql")
    static final MySQLContainer MYSQL = new MySQLContainer(MySqlTestImage.NAME)
            .withUrlParam("connectTimeout", "3000").withUrlParam("socketTimeout", "30000");

    @Autowired private SmartLinkUseCase links;
    @Autowired private SmartLinkRepository repository;
    @Autowired private LinkCreationReservationPort reservations;
    @Autowired private LinkCodeKeyGuardPort guardPort;
    @Autowired private PublicLinkOriginPort publicOrigin;
    @Autowired private TargetUrlPort targets;
    @Autowired private Clock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearLinks() {
        jdbc.update("DELETE FROM link_creation_requests");
        jdbc.update("DELETE FROM smart_links");
    }

    @Test
    @DisplayName("키 교체 전후의 생성 요청은 각각 저장한 키로 같은 단축 URL을 재생한다")
    void persistsWinningKeyAndReplaysAcrossRotation() {
        var oldCommand = command();
        var oldLink = links.createLink(oldCommand);
        var currentCommand = command();
        var properties = new LinkCodeProperties(LEGACY, "k202609", Map.of("k202609", CURRENT));

        var oldReplay = withKeys(properties, service -> service.createLink(oldCommand));
        var currentLink = withKeys(properties, service -> service.createLink(currentCommand));
        var currentReplay = links.createLink(currentCommand);

        assertThat(oldReplay.shortUrl()).isEqualTo(oldLink.shortUrl());
        assertThat(currentReplay.shortUrl()).isEqualTo(currentLink.shortUrl());
        assertThat(currentReplay.replayed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT key_id FROM link_creation_requests WHERE link_id = UUID_TO_BIN(?)",
                String.class, oldLink.link().id().toString())).isEqualTo("legacy");
        assertThat(jdbc.queryForObject("SELECT key_id FROM link_creation_requests WHERE link_id = UUID_TO_BIN(?)",
                String.class, currentLink.link().id().toString())).isEqualTo("k202609");
    }

    @Test
    @DisplayName("키 ID의 비밀값 변경과 아직 재생에 필요한 키 제거는 DB 결합에서 거부한다")
    void rejectsChangedOrMissingRequiredKey() {
        withKeys(new LinkCodeProperties(LEGACY, "k202609", Map.of("k202609", CURRENT)),
                service -> service.createLink(command()));

        assertThatThrownBy(() -> withKeys(new LinkCodeProperties(LEGACY), service -> null))
                .isInstanceOf(LinkCodeKeyBindingException.class);
        assertThatThrownBy(() -> withKeys(new LinkCodeProperties(
                LEGACY, "k202609", Map.of("k202609", "different-key-with-at-least-thirty-two-characters")
        ), service -> null)).isInstanceOf(LinkCodeKeyBindingException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM smart_links", Integer.class)).isEqualTo(1);
    }

    private <T> T withKeys(LinkCodeProperties properties, Function<SmartLinkService, T> operation) {
        var codes = new SecureLinkCodeAdapter(properties);
        var guard = new LinkCodeKeyGuard(codes, guardPort);
        var service = new SmartLinkService(repository, reservations, codes, guard, publicOrigin, targets, clock);
        return new TransactionTemplate(transactionManager).execute(status -> {
            guard.verifyOrBind();
            return operation.apply(service);
        });
    }

    private CreateLinkCommand command() {
        return new CreateLinkCommand(CreationIdempotencyKey.parseRequest(UUID.randomUUID().toString()),
                TargetSystem.ROUND, "/room/abcd-efgh-jkmn", LinkPurpose.MEETING_ENTRY, null, null);
    }
}
