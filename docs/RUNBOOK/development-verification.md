# 개발 검증 절차

로컬에서는 변경한 동작부터 검증한다. CI와 배포에 필요한 검증은
[README](../../README.md#검증)와 [CI 설정](../../.github/workflows/ci.yml)을 따른다.

## 변경별 검증 선택

| 변경 | 먼저 확인할 범위 |
| --- | --- |
| 문구·문서 검토 | 근거와 파일 위치 확인. 동작 확인이 필요하지 않으면 빌드·테스트를 생략한다. |
| 문서·주석·테스트 표시 이름 | 변경 내용과 `git diff --check`. 문서 경로·제목을 바꿨다면 관련 링크도 확인한다. |
| 오류 안내 문구 | 해당 오류의 기존 테스트. 관리 오류 처리 예: `./gradlew :adapter-in-web:test --tests '*GlobalExceptionHandlerTest'` |
| 도메인 정책 | `./gradlew :domain:test` |
| 애플리케이션 흐름 | `./gradlew :application:test` |
| HTTP 형식·상태·인증 | `./gradlew :adapter-in-web:test` |
| Slack·Discord·Healthchecks 웹훅 템플릿·URL 예시 | `python3 tools/verify-webhooks.py --output /tmp/baton-go-webhooks`. 로컬 Docker가 필요하며 외부 메시지는 전송하지 않는다. |
| Java 패키지·모듈 의존 | `./gradlew --no-daemon :bootstrap:test --tests '*ArchitectureRulesTest'` |
| JPA·SQL·Flyway·DB 동시성 | `./gradlew --no-daemon :bootstrap:mysqlTest` |
| Redis 요청 제한·연결 설정 | `./gradlew --no-daemon :bootstrap:redisTest` |
| Prometheus 수집·경보 | [설정·규칙 검증](prometheus-alerts.md#로컬ci-검증). Kubernetes 권한 변경은 Kustomize와 strict schema 검증을 함께 실행한다. Java·DB 테스트는 생략한다. |
| 여러 모듈에 영향을 주는 변경 | `./gradlew test`. 실행 패키지·의존성·배포 변경은 README의 추가 검증을 적용한다. |

테스트 클래스를 알면 `--tests`로 좁혀 실행한다. 영향이 다른 계층까지 이어질 때만 범위를 넓힌다.
여러 Gradle 작업이 필요하면 한 번에 실행하며, 선행 컴파일은 Gradle에 맡긴다.
일반 `test`는 MySQL·Redis 태그를 제외하므로 통합 테스트 결과를 대신하지 않는다.

## 실행과 결과 재사용

1. `git rev-parse HEAD`와 `git status --short`로 작업 시작 리비전과 기존 변경을 기록한다.
   변경 파일과 검증할 동작을 확인하고 명령을 선택한다. 리뷰 요청이면 먼저 읽기 전용으로 판단한다.
2. 파일을 작성하거나 수정한 직후 파일 전체와 `git diff --check -- <파일>`을 확인한다. Java는 컴파일,
   설정은 해당 파서, 문서는 링크·표현처럼 파일 형식에 맞는 가까운 검사를 먼저 실행한다. 새 파일은
   `git diff`에 나오지 않을 수 있으므로 내용을 직접 읽는다.
3. 한 동작의 변경이 끝나면 변경별 검증을 실행한다. Java 패키지나 모듈 의존을 바꿨다면
   `ArchitectureRulesTest`로 다음 규칙을 확인한다.

   - `domain`은 `application`·`adapter`·`bootstrap`에 의존하지 않는다.
   - `application`은 `adapter` 구현체와 `bootstrap`에 의존하지 않는다.
   - `@RestController`는 출력 포트에 직접 의존하지 않는다.
   - 인바운드·아웃바운드 어댑터 구현체는 서로 직접 의존하지 않는다.

4. 기존 Gradle 캐시와 증분 검증을 사용한다. Gradle이 추적하지 못하는 실행 환경이 바뀌었거나
   캐시 문제를 확인할 때는 `--rerun-tasks`로 필요한 테스트를 다시 실행한다. `clean`은 빌드 산출물
   문제를 확인할 때, `--refresh-dependencies`는 README에 명시한 의존성 변경 검증 등에 사용한다.
5. 실패하면 코드·테스트 실패인지 권한·의존성·서비스 시작 실패인지 구분한다.
   원인을 바꾼 뒤 실패한 범위부터 다시 확인하며, 실행되지 않은 테스트를 통과로 기록하지 않는다.
6. 검증 대상 코드·테스트·의존성·설정·실행 환경이 같다고 확인되면 기존 성공 결과를 사용한다.
   문서 수정이나 커밋 생성만으로 테스트를 반복하지 않는다. 입력 변경 여부가 불명확하면 필요한 범위를 재실행한다.
7. 커밋 전에는 해당 변경 묶음의 전체 diff를 읽는다. 작업 종료 직전에는 `git status --short`,
   `git diff --check`, 시작 리비전부터 현재 `HEAD`까지의 diff와 남은 미커밋 diff를 확인한다.
   새 파일은 상태 목록에서 빠짐없이 찾아 내용을 읽는다.
8. 필요한 검증이 끝나면 후속 작업으로 넘길 때 기존 HANDOFF 기록에
   체크아웃·리비전과 미커밋 변경, 실행 명령·범위·결과, 미실행 항목과 이유, 필요한 로그 위치만 갱신한다.

Gradle 캐시 잠금이나 로컬 포트 권한 오류는 실행 환경 문제다. 현재 권한을 확인해 정식 승인 절차를
사용하며, 같은 조건으로 반복 실행하거나 잠금·캐시를 임의 삭제하지 않는다.

검증 도구는 기존 실행 환경에서 필요한 모듈을 쓸 수 있는지 먼저 확인한다. 같은 의존성 오류가 나면
다른 Python을 추측해서 반복 실행하지 말고, 동작을 확인한 환경을 사용한다.
