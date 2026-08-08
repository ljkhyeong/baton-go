package com.personal.batongo.adapter.out.persistence.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LinkCodeKeyGuardPersistenceAdapterTest {

    @Test
    @DisplayName("guard 조회 행 문자열은 파생 규약과 키 fingerprint를 노출하지 않는다")
    void redactsGuardRowStringRepresentation() throws ReflectiveOperationException {
        Class<?> guardRowType = Arrays.stream(
                        LinkCodeKeyGuardPersistenceAdapter.class.getDeclaredClasses()
                )
                .filter(type -> type.getSimpleName().equals("GuardRow"))
                .findFirst()
                .orElseThrow();
        Constructor<?> constructor = guardRowType.getDeclaredConstructor(
                String.class,
                String.class
        );
        constructor.setAccessible(true);

        Object guardRow = constructor.newInstance(
                "sensitive-derivation-version",
                "sensitive-key-fingerprint"
        );

        assertThat(guardRow.toString())
                .isEqualTo("GuardRow[redacted]")
                .doesNotContain("sensitive-derivation-version")
                .doesNotContain("sensitive-key-fingerprint");
    }
}
