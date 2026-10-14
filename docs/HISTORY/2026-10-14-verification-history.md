# 2026-10-14 검증 기록

GO `c6ee6f9` 이후 HANDOFF `최근 검증`에서 현재 판단에 필요 없는 이전 항목을 옮겼다.
아래 상태와 미실행 항목은 각 기록의 확인 시점 기준이다. 현재 상태와 다음 작업은
[HANDOFF](../../HANDOFF.md)를 확인한다. 더 이전 기록은
[2026-10-12 검증 기록](2026-10-12-verification-history.md)에 있다.

## 검증 기록

- 6차 정리는 미커밋 변경이 없는 `main`의 `df44c22`에서 시작해 `1c6c4c9`·`e91d271`·`1814e53`·`0e4b3fb`·
  `f3e8760`·`fca438e`·`8801c08`·`36389fa`·`4209fd9`·`5d7d1d8`에 저장했다. 5차 결과를 기준으로 감사 워크플로를
  다시 돌려 후보 38건을 찾았고, 후보마다 실현성·동작·보안운영 반박 검증 3개를 거쳤다. 3관점을 모두 통과한 32건을
  적용했다. 이어서 남은 후보를 찾는 감사를 한 회차 더 돌려 후보 5건 중 반박 검증 2개를 모두 통과한 4건을 적용했다.
  주요 내용은 다음과 같다.
  - 생성 예약 때 `request_hash`를 저장하는 V2를 추가해 정리 단계가 대상 원문을 다시 읽지 않는다. 보존 정리 포트는
    링크 ID만 다룬다. 정리 전 예약이 남은 로컬 MySQL 볼륨에는 V2를 적용할 수 없으므로 다시 만든다.
  - 기본 링크 코드 키를 `BATONGO_LINKCODE_KEYS_DEFAULT` 하나로 통일하고 `BATON_GO_LINK_CODE_SECRET` 경로를 지웠다.
    키 교체 오버레이는 Compose `!reset`(2.24.4 이상)을, Kubernetes는 필수 키 묶음 Secret을 쓴다.
  - 비밀값이 필요 없는 해시를 `LinkCodePort`에서 뺐다. 멱등성 키는 `CreationIdempotencyKey#hash()`, 공개 코드는
    `LinkCodeHash.of`가 맡고 `IssuedLinkCode`와 어댑터의 SHA-256 중복을 지웠다. 저장 `code_hash` 값은 고정 벡터로
    같음을 확인했다.
  - `PublicLinkErrorPage`를 `PublicLinkExceptionHandler`에 합치고 `InvalidRequestException` 팩터리를 생성자 하나로 줄였다.
  - 웹·MySQL 테스트의 반복 속성을 테스트 속성 파일로 모으고 JDBC·PEM·RSA 키·URI 확장을 `DriverManagerDataSource`·
    `JdbcClient`·`PemContent`·`RSAKeyGenerator`·MockMvc URI 변수로 바꿨다. MySQL 테스트 이미지는 `compose.yml`에서 읽는다.
  - REST Docs 관리 예시는 문서 전처리 기본값으로 Authorization 자리표시자를 넣어 일괄 조회·검색 예시 4개에도 헤더가 보인다.
  - Dockerfile은 모듈 디렉터리를 복사하고 JDK 기본값과 같은 `java.io.tmpdir` 지정을 지웠다. 예시·overlay·CI의 기본값과 같은
    선택 설정 줄을 지웠다.

  적용하지 않은 후보는 다음과 같다.
  - MySQL JWT 시작 통합 테스트 흡수: 슬라이스 테스트가 실제 시작 구성의 검증 범위를 대신하지 못한다.
  - Compose 환경 변수의 값 없는 전달: 빈 값 입력의 동작 차이를 확정하지 못했다.
  - `deploy/app.env.example` 삭제: 사용자가 요청한 산출물이고 로컬 복사 절차가 참조한다.
  - external 테스트 의존성 통합: 이전에 정한 최소 의존 결정을 되돌린다.
  - Redis 자격 증명 `envFrom` 축약: Secret의 모든 키가 주입돼 허용 목록이 약해진다.
  - 생성 재시도 동일성을 예약 `request_hash` 비교 하나로 통일: 운영 코드 순감이 작고 저장 링크 변조 거부 검증이 약해진다.

  운영 코드 27개 파일에서 287줄을 지우고 175줄을 더했다. 전체로는 80개 파일에서 1,004줄을 지우고 608줄을 더했다.
  Java 21에서 `./gradlew --no-daemon build :adapter-in-web:apiContractDocs :bootstrap:mysqlTest :bootstrap:redisTest`를
  통과했다. 도메인 72·애플리케이션 43·웹 120·외부 21·bootstrap 29·MySQL 28·Redis 3개에 실패·제외가 없고 REST Docs 조각은
  84개다. MySQL 수는 `MySqlImageContractTest` 삭제로 1개 줄었다. `docker build`와 CI 운영 이미지 기동 단계의 로컬 실행이
  통과했고, 마이그레이션 전용 실행에서 V2가 적용됐다. `docker compose config`(기본·키 교체 오버레이)와 `kubectl kustomize`
  렌더링이 통과했고 실제 클러스터 적용은 하지 않았다. 문서 링크 검사는 문제 0건이다. `mysql:8.4.11`에서 RUNBOOK의 V2 판별
  조회가 미적용·적용 상태를 구분하고, 정리 전 예약 때문에 실패한 V2가 V1 상태를 그대로 남기는 것을 확인했다. 2차 결과의
  5관점 diff 검토와 지적별 반박 검증에서 문서 누락 2건만 확정돼 `36389fa`로 고쳤고, 추가 감사 적용분의 diff 검토에서는
  결함이 없었다. 로그와 감사 결과는 세션 스크래치패드의 `full-verify-r3.log`·`smoke-run-r3.log`·`docker-build-r3.log`·
  `audit-round2.json`·`audit-round3.json`이다. 로컬 무시 파일 `deploy/app.env`의 키 이름은 바꾸지 않았다. 원격 반영은 하지
  않았다.

- 5차 정리는 미커밋 변경이 없는 `main`의 `971f5b3`에서 시작해 `888fe7b`·`8195880`·`4edb104`·`f0c9f85`·`17fa541`에
  저장했다. JDK·Spring 표준 API로 대체할 직접 구현과 불필요 코드를 감사 워크플로로 찾았다. 모듈·관점별 탐색 7개와
  완전성 비평이 후보 36건을 냈고, 후보마다 실현성·동작·보안운영 반박 검증 3개를 거쳤다. 통과한 후보를 적용했으며
  주요 내용은 다음과 같다.
  - 예외별 처리기 16개를 예외 유형 switch 하나로 합쳤다.
  - 원인·스택 직접 수집을 Boot `StandardStackTracePrinter`로 바꾸고 요청 ID를 MDC 하나에서 읽는다.
  - 요청 제한 인터셉터는 `includeHttpMethods(GET, HEAD)`로 자기 등록하고 0/Retry-After 초를 반환한다.
  - Bearer 실패 처리기를 진입점 메서드 참조로 바꿨다.
  - `LinkCodeKeyGuard`·`StoredLinkScan`·`DatabaseMigrationRunner`·`PublicResolverWebMvcConfiguration`을 지웠다.
    시작 키 검사는 Boot `TransactionTemplate` 안에서 실행한다.
  - 재시도 동일성은 `LinkCreationFingerprint` 하나로 판정한다.
  - 단일 조회의 읽기 전용 트랜잭션을 없앴다.
  - 보존 정리는 주입한 `JdbcClient`로 링크별 실행하고, 예약 재조회는 `single()`을 쓴다.
  - `LinkCodeProperties` 기본값을 `@DefaultValue`로 옮기고 `CreationIdempotencyKey`·키 식별 record를 단순화했다.
  - 게이지를 `MeterRegistry#gauge`로 등록하고 중복 Boot 의존성 선언과 Boot 기본값 설정을 지웠다.
  - `/actuator/metrics` 노출을 지웠다.
  - 실제 서버·DB 테스트를 `RestTestClient`·`JdbcTestUtils`·`@TestBean`으로 바꿨다.

  적용하지 않은 후보는 다음과 같다.
  - `application.yml`의 `baton-go` 환경 변수 매핑 제거: 빈 값이 `@DefaultValue`로 대체돼 시작 거부 계약이 깨진다.
  - 단일 `BATON_GO_LINK_CODE_SECRET` 경로 제거: Compose 오버레이와 Kubernetes Secret 구성을 함께 바꿔야 해 보류했다.
  - 대상 허용 테스트 통합: 메서드 이름이 사실과 달라지고 줄이 길어진다.

  운영 코드 27개 파일에서 662줄을 지우고 304줄을 더했다. 전체로는 55개 파일에서 1,120줄을 지우고 620줄을 더했다.
  Java 21에서 `./gradlew --no-daemon build :adapter-in-web:apiContractDocs :bootstrap:mysqlTest :bootstrap:redisTest`를
  통과했다. 도메인 72·애플리케이션 41·웹 120·외부 22·bootstrap 29·MySQL 29·Redis 3개에 실패·제외가 없고 REST Docs
  조각은 84개다. `docker build`가 성공했고 CI 운영 이미지 기동 단계도 같은 스크립트로 로컬 실행해 통과했다. 이 단계는
  공개 오류·429·지표 0 노출과 마이그레이션 전용 실행을 확인한다. 중간 커밋 3개는 별도 작업 트리에서 `test`와
  `:bootstrap:compileTestJava`를 통과했다. 요청 제한 메서드 필터를 지운 변이에서는 새 OPTIONS 단언이 실패해
  회귀를 잡는 것을 확인했다. 의존성 선언 변경 전후의 런타임 클래스패스가 같고, 검증 메타데이터 변경은 없다.
  문서 링크 검사는 40개 파일에서 문제 0건이다. 계약·보안·트랜잭션·테스트·문서 관점의 diff 검토와 지적별 반박
  검증에서 확정 결함은 없었다. 남은 차이는 `RestTestClient` 전환으로 실제 서버 테스트의 요청별 5초 제한이
  없어진 것이다. 로그와 감사 결과는 세션 스크래치패드(`46e02272-…/scratchpad`)의 `full-verify.log`·
  `smoke-run.log`·`docker-build.log`·`commit-check-*.log`·`audit-final.json`이다. 이전 검증 항목 5개는
  [2026-10-06 검증 기록](2026-10-06-verification-history.md)으로 옮겼다. 원격 반영은 하지 않았다.
