package com.personal.batongo.domain.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TrustedTargetPolicyTest {

    private static final String TEAM_ID = "8e448211-66ae-44ab-9888-c4960648c22b";
    private static final String SEASON_ID = "713d9cb7-2842-4f9f-b3cc-e31d98c6238a";
    private static final String BATON_PATH = batonPath(TEAM_ID, SEASON_ID);
    private static final String ROUND_PATH = "/room/abcd-efgh-jkmn";

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("allowedV1Targets")
    @DisplayName("v1에서 정의한 두 대상 조합만 허용한다")
    void allowsExactlyTwoV1TargetTriples(
            String description,
            TargetSystem targetSystem,
            LinkPurpose purpose,
            String targetPath
    ) {
        TrustedTarget target = TrustedTargetPolicy.requireAllowed(
                targetSystem,
                purpose,
                targetPath
        );

        assertThat(target.targetSystem()).isEqualTo(targetSystem);
        assertThat(target.purpose()).isEqualTo(purpose);
        assertThat(target.targetPath()).isEqualTo(targetPath);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(strings = {
            "8e448211-66ae-14ab-8888-c4960648c22b",
            "8e448211-66ae-24ab-9888-c4960648c22b",
            "8e448211-66ae-34ab-a888-c4960648c22b",
            "8e448211-66ae-44ab-b888-c4960648c22b",
            "8e448211-66ae-54ab-8888-c4960648c22b"
    })
    @DisplayName("BATON 식별자는 UUID version 1부터 5와 RFC variant를 허용한다")
    void allowsSupportedUuidVersionsAndVariants(String identifier) {
        TrustedTarget target = TrustedTargetPolicy.requireAllowed(
                TargetSystem.BATON,
                LinkPurpose.NAVIGATION,
                batonPath(identifier, identifier)
        );

        assertThat(target.targetPath()).isEqualTo(batonPath(identifier, identifier));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("invalidBatonUuidPaths")
    @DisplayName("BATON 식별자의 UUID version과 variant와 대소문자를 엄격히 검증한다")
    void rejectsInvalidUuidVersionVariantAndCase(String description, String targetPath) {
        assertRejected(TargetSystem.BATON, LinkPurpose.NAVIGATION, targetPath);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(strings = {
            "/room/abcd-efgh-jkmn",
            "/room/pqrs-tuvw-xyz2",
            "/room/3456-789a-bcde"
    })
    @DisplayName("ROUND 식별자는 고정된 lowercase alphabet을 허용한다")
    void allowsRoundAlphabet(String targetPath) {
        TrustedTarget target = TrustedTargetPolicy.requireAllowed(
                TargetSystem.ROUND,
                LinkPurpose.MEETING_ENTRY,
                targetPath
        );

        assertThat(target.targetPath()).isEqualTo(targetPath);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("invalidRoundPaths")
    @DisplayName("ROUND 식별자의 alphabet과 대소문자와 그룹 형식을 엄격히 검증한다")
    void rejectsInvalidRoundAlphabetCaseAndGroups(String description, String targetPath) {
        assertRejected(TargetSystem.ROUND, LinkPurpose.MEETING_ENTRY, targetPath);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("nonCanonicalPathShapes")
    @DisplayName("대상 경로의 공백과 trailing slash와 추가 segment를 거부한다")
    void rejectsWhitespaceTrailingSlashAndAdditionalSegments(
            String description,
            TargetSystem targetSystem,
            LinkPurpose purpose,
            String targetPath
    ) {
        assertRejected(targetSystem, purpose, targetPath);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("crossedTargetCombinations")
    @DisplayName("대상 시스템과 목적과 locator를 교차 조합할 수 없다")
    void rejectsCrossedSystemPurposeAndLocator(
            String description,
            TargetSystem targetSystem,
            LinkPurpose purpose,
            String targetPath
    ) {
        assertRejected(targetSystem, purpose, targetPath);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("resourceOpenTargets")
    @DisplayName("예약된 RESOURCE_OPEN 목적은 v1에서 사용할 수 없다")
    void rejectsReservedResourceOpenPurpose(
            String description,
            TargetSystem targetSystem,
            String targetPath
    ) {
        assertRejected(targetSystem, LinkPurpose.RESOURCE_OPEN, targetPath);
    }

    private static Stream<Arguments> allowedV1Targets() {
        return Stream.of(
                Arguments.of(
                        "BATON navigation",
                        TargetSystem.BATON,
                        LinkPurpose.NAVIGATION,
                        BATON_PATH
                ),
                Arguments.of(
                        "ROUND meeting entry",
                        TargetSystem.ROUND,
                        LinkPurpose.MEETING_ENTRY,
                        ROUND_PATH
                )
        );
    }

    private static Stream<Arguments> invalidBatonUuidPaths() {
        return Stream.of(
                Arguments.of(
                        "version 0",
                        batonPath("8e448211-66ae-04ab-9888-c4960648c22b", SEASON_ID)
                ),
                Arguments.of(
                        "version 6",
                        batonPath(TEAM_ID, "713d9cb7-2842-6f9f-b3cc-e31d98c6238a")
                ),
                Arguments.of(
                        "variant 7",
                        batonPath("8e448211-66ae-44ab-7888-c4960648c22b", SEASON_ID)
                ),
                Arguments.of(
                        "variant c",
                        batonPath(TEAM_ID, "713d9cb7-2842-4f9f-c3cc-e31d98c6238a")
                ),
                Arguments.of(
                        "uppercase team UUID",
                        batonPath("8E448211-66ae-44ab-9888-c4960648c22b", SEASON_ID)
                ),
                Arguments.of(
                        "uppercase season UUID",
                        batonPath(TEAM_ID, "713d9cb7-2842-4f9f-B3cc-e31d98c6238a")
                )
        );
    }

    private static Stream<Arguments> invalidRoundPaths() {
        return Stream.of(
                Arguments.of("금지 alphabet i", "/room/ibcd-efgh-jkmn"),
                Arguments.of("금지 alphabet l", "/room/lbcd-efgh-jkmn"),
                Arguments.of("금지 alphabet o", "/room/obcd-efgh-jkmn"),
                Arguments.of("금지 alphabet 0", "/room/0bcd-efgh-jkmn"),
                Arguments.of("금지 alphabet 1", "/room/1bcd-efgh-jkmn"),
                Arguments.of("uppercase", "/room/Abcd-efgh-jkmn"),
                Arguments.of("짧은 첫 그룹", "/room/abc-efgh-jkmn"),
                Arguments.of("긴 둘째 그룹", "/room/abcd-efgha-jkmn"),
                Arguments.of("누락된 셋째 그룹", "/room/abcd-efgh"),
                Arguments.of("추가된 넷째 그룹", "/room/abcd-efgh-jkmn-pqrs")
        );
    }

    private static Stream<Arguments> nonCanonicalPathShapes() {
        return Stream.of(
                Arguments.of(
                        "BATON 앞 공백",
                        TargetSystem.BATON,
                        LinkPurpose.NAVIGATION,
                        " " + BATON_PATH
                ),
                Arguments.of(
                        "BATON 뒤 공백",
                        TargetSystem.BATON,
                        LinkPurpose.NAVIGATION,
                        BATON_PATH + " "
                ),
                Arguments.of(
                        "BATON trailing slash",
                        TargetSystem.BATON,
                        LinkPurpose.NAVIGATION,
                        BATON_PATH + "/"
                ),
                Arguments.of(
                        "BATON 추가 segment",
                        TargetSystem.BATON,
                        LinkPurpose.NAVIGATION,
                        BATON_PATH + "/resources/123"
                ),
                Arguments.of(
                        "ROUND 앞 공백",
                        TargetSystem.ROUND,
                        LinkPurpose.MEETING_ENTRY,
                        " " + ROUND_PATH
                ),
                Arguments.of(
                        "ROUND 뒤 공백",
                        TargetSystem.ROUND,
                        LinkPurpose.MEETING_ENTRY,
                        ROUND_PATH + " "
                ),
                Arguments.of(
                        "ROUND trailing slash",
                        TargetSystem.ROUND,
                        LinkPurpose.MEETING_ENTRY,
                        ROUND_PATH + "/"
                ),
                Arguments.of(
                        "ROUND 추가 segment",
                        TargetSystem.ROUND,
                        LinkPurpose.MEETING_ENTRY,
                        ROUND_PATH + "/join"
                )
        );
    }

    private static Stream<Arguments> crossedTargetCombinations() {
        return Stream.of(
                Arguments.of(
                        "BATON과 MEETING_ENTRY",
                        TargetSystem.BATON,
                        LinkPurpose.MEETING_ENTRY,
                        BATON_PATH
                ),
                Arguments.of(
                        "ROUND와 NAVIGATION",
                        TargetSystem.ROUND,
                        LinkPurpose.NAVIGATION,
                        ROUND_PATH
                ),
                Arguments.of(
                        "BATON과 ROUND locator",
                        TargetSystem.BATON,
                        LinkPurpose.NAVIGATION,
                        ROUND_PATH
                ),
                Arguments.of(
                        "ROUND와 BATON locator",
                        TargetSystem.ROUND,
                        LinkPurpose.MEETING_ENTRY,
                        BATON_PATH
                )
        );
    }

    private static Stream<Arguments> resourceOpenTargets() {
        return Stream.of(
                Arguments.of("BATON RESOURCE_OPEN", TargetSystem.BATON, BATON_PATH),
                Arguments.of("ROUND RESOURCE_OPEN", TargetSystem.ROUND, ROUND_PATH)
        );
    }

    private static String batonPath(String teamId, String seasonId) {
        return "/teams/" + teamId + "/seasons/" + seasonId;
    }

    private static void assertRejected(
            TargetSystem targetSystem,
            LinkPurpose purpose,
            String targetPath
    ) {
        assertThatThrownBy(() -> TrustedTargetPolicy.requireAllowed(
                targetSystem,
                purpose,
                targetPath
        )).isInstanceOf(LinkValidationException.class);
    }
}
