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

        assertThat(TrustedTargetPolicy.isAllowed(
                targetSystem.name(),
                purpose.name(),
                targetPath
        )).isTrue();
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
    @DisplayName("BATON 식별자는 UUID 버전 1~5와 RFC 변형을 허용한다")
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
    @DisplayName("BATON 식별자의 UUID 버전·RFC 변형·대소문자를 정확히 검증한다")
    void rejectsInvalidUuidVersionVariantAndCase(String description, String targetPath) {
        assertRejected(TargetSystem.BATON, LinkPurpose.NAVIGATION, targetPath);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(strings = {
            "/room/pqrs-tuvw-xyz2",
            "/room/3456-789a-bcde"
    })
    @DisplayName("ROUND 식별자는 정해진 소문자 영문만 허용한다")
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
    @DisplayName("ROUND 식별자의 영문 범위·대소문자·그룹 형식을 정확히 검증한다")
    void rejectsInvalidRoundAlphabetCaseAndGroups(String description, String targetPath) {
        assertRejected(TargetSystem.ROUND, LinkPurpose.MEETING_ENTRY, targetPath);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("nonCanonicalPathShapes")
    @DisplayName("대상 경로의 공백·끝 슬래시·추가 경로 구간을 거부한다")
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
    @DisplayName("대상 시스템·목적·경로를 정의되지 않은 조합으로 사용할 수 없다")
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
                        "BATON 이동",
                        TargetSystem.BATON,
                        LinkPurpose.NAVIGATION,
                        BATON_PATH
                ),
                Arguments.of(
                        "ROUND 회의 입장",
                        TargetSystem.ROUND,
                        LinkPurpose.MEETING_ENTRY,
                        ROUND_PATH
                )
        );
    }

    private static Stream<Arguments> invalidBatonUuidPaths() {
        return Stream.of(
                Arguments.of(
                        "버전 0",
                        batonPath("8e448211-66ae-04ab-9888-c4960648c22b", SEASON_ID)
                ),
                Arguments.of(
                        "버전 6",
                        batonPath(TEAM_ID, "713d9cb7-2842-6f9f-b3cc-e31d98c6238a")
                ),
                Arguments.of(
                        "변형 7",
                        batonPath("8e448211-66ae-44ab-7888-c4960648c22b", SEASON_ID)
                ),
                Arguments.of(
                        "변형 c",
                        batonPath(TEAM_ID, "713d9cb7-2842-4f9f-c3cc-e31d98c6238a")
                ),
                Arguments.of(
                        "대문자 팀 UUID",
                        batonPath("8E448211-66ae-44ab-9888-c4960648c22b", SEASON_ID)
                ),
                Arguments.of(
                        "대문자 회차 UUID",
                        batonPath(TEAM_ID, "713d9cb7-2842-4f9f-B3cc-e31d98c6238a")
                )
        );
    }

    private static Stream<Arguments> invalidRoundPaths() {
        return Stream.of(
                Arguments.of("금지 문자 i", "/room/ibcd-efgh-jkmn"),
                Arguments.of("금지 문자 l", "/room/lbcd-efgh-jkmn"),
                Arguments.of("금지 문자 o", "/room/obcd-efgh-jkmn"),
                Arguments.of("금지 문자 0", "/room/0bcd-efgh-jkmn"),
                Arguments.of("금지 문자 1", "/room/1bcd-efgh-jkmn"),
                Arguments.of("대문자", "/room/Abcd-efgh-jkmn"),
                Arguments.of("짧은 첫 그룹", "/room/abc-efgh-jkmn"),
                Arguments.of("긴 둘째 그룹", "/room/abcd-efgha-jkmn"),
                Arguments.of("누락된 셋째 그룹", "/room/abcd-efgh"),
                Arguments.of("추가된 넷째 그룹", "/room/abcd-efgh-jkmn-pqrs")
        );
    }

    private static Stream<Arguments> nonCanonicalPathShapes() {
        return Stream.of(
                Arguments.of(
                        "BATON null",
                        TargetSystem.BATON,
                        LinkPurpose.NAVIGATION,
                        null
                ),
                Arguments.of(
                        "BATON 빈 문자열",
                        TargetSystem.BATON,
                        LinkPurpose.NAVIGATION,
                        ""
                ),
                Arguments.of(
                        "BATON 공백만 있는 경로",
                        TargetSystem.BATON,
                        LinkPurpose.NAVIGATION,
                        "   "
                ),
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
                        "BATON 끝 슬래시",
                        TargetSystem.BATON,
                        LinkPurpose.NAVIGATION,
                        BATON_PATH + "/"
                ),
                Arguments.of(
                        "BATON 추가 경로 구간",
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
                        "ROUND 끝 슬래시",
                        TargetSystem.ROUND,
                        LinkPurpose.MEETING_ENTRY,
                        ROUND_PATH + "/"
                ),
                Arguments.of(
                        "ROUND 추가 경로 구간",
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
                        "BATON과 ROUND 경로",
                        TargetSystem.BATON,
                        LinkPurpose.NAVIGATION,
                        ROUND_PATH
                ),
                Arguments.of(
                        "ROUND와 BATON 경로",
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
        assertThat(TrustedTargetPolicy.isAllowed(
                targetSystem.name(),
                purpose.name(),
                targetPath
        )).isFalse();
        assertThatThrownBy(() -> TrustedTargetPolicy.requireAllowed(
                targetSystem,
                purpose,
                targetPath
        )).isInstanceOf(LinkValidationException.class);
    }
}
