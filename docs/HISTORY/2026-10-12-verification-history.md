# 2026-10-12 검증 기록

GO `f7459e1` 이후 HANDOFF `최근 검증`에서 현재 판단에 필요 없는 이전 항목을 옮겼다.
아래 상태와 미실행 항목은 각 기록의 확인 시점 기준이다. 현재 상태와 다음 작업은
[HANDOFF](../../HANDOFF.md)를 확인한다. 더 이전 기록은
[2026-10-06 검증 기록](2026-10-06-verification-history.md)에 있다.

## 검증 기록

- 의존성 보안 갱신은 미커밋 변경이 없는 `main`의 `3b07f2d`에서 시작해 `6135d99`와 문서 커밋에 저장했다. 푸시 때 확인한
  Dependabot 경고 10건은 Tomcat `tomcat-embed-core` 11.0.24(치명 3, GHSA-gcx9-497g-6cp6 등)와 Jackson
  `jackson-core`·`jackson-databind` 3.1.5(높음 5·보통 2)였다. 최신 안정 Boot가 4.1.1이라 BOM 갱신으로 해결할 수 없어
  `enforcedPlatform` 위에 `eachDependency` 규칙을 두고 `org.apache.tomcat.embed`를 11.0.26, `tools.jackson*`을 3.1.7로
  바꿨다. 검증 메타데이터에 새 버전 7개를 추가하고, 원래 파일을 치운 `--dry-run` 결과와 대조해 쓰이지 않는 Tomcat
  11.0.24 항목 3개를 지웠다. Jackson 3.1.5는 Spring Boot Gradle 플러그인 빌드 경로에서 계속 쓰여 남겼다.
  Java 21에서 결과 디렉터리를 비운 뒤 `./gradlew --no-daemon --refresh-dependencies build :adapter-in-web:apiContractDocs
  :bootstrap:mysqlTest :bootstrap:redisTest`를 통과했다. 도메인 72·애플리케이션 41·웹 120·외부 20·bootstrap 29·
  MySQL 29·Redis 3개에 실패·제외가 없고 REST Docs 조각은 84개다. `docker build --no-cache`가 성공했고 실행 jar에
  Tomcat 11.0.26·Jackson 3.1.7만 들어 있음을 확인했다. CI 운영 이미지 기동 단계도 같은 스크립트로 로컬 실행해 통과했다.
  경고 종료는 원격 CI의 의존성 제출 뒤 GitHub에서 확인한다. 원격 반영은 하지 않았다.
- Redis 설정 보안 보완은 미커밋 변경이 없는 `main`의 `6a4ad30`에서 시작해 `67117a6`과 문서 커밋에 저장했다.
  4차 정리의 `spring.data.redis.url`은 Spring Boot가 형식 오류 때 URL 전체를 시작 실패 메시지에 출력하고 자격 증명도
  URL에서만 읽는 것을 Boot 4.1.1 클래스에서 확인했다. 그래서 URL과 `BATON_GO_REDIS_URI`를 없애고 주소·TLS·DB 번호는
  `SPRING_DATA_REDIS_HOST`·`PORT`·`SSL_ENABLED`·`DATABASE`, 자격 증명은 Kubernetes Secret의
  `SPRING_DATA_REDIS_USERNAME`·`PASSWORD`로 주입한다. Compose의 기본 주소 우회도 함께 없앴고, 쓰지 않는 Spring Data
  Redis 저장소 스캔을 껐다. Java 21에서 `./gradlew --no-daemon :bootstrap:test :bootstrap:redisTest :bootstrap:mysqlTest`를
  통과했다. bootstrap 29·Redis 3·MySQL 29개에 실패·제외가 없고 시작 로그의 저장소 스캔이 사라졌다. `kubectl kustomize`로
  운영 오버레이의 Secret 참조를, `docker compose config`로 빈 값의 기본값 적용을 확인했다. `docker build`와 CI 운영
  이미지 기동 단계를 같은 스크립트로 로컬 실행해 통과했다. kubeconform은 로컬에 없어 원격 CI에서 확인한다.
  원격 반영은 하지 않았다.
- 4차 정리는 미커밋 변경이 없는 `main`의 `ee5fb6e`에서 시작해 `3b42af5`·`cab0abd`와 문서 커밋에 저장했다.
  분산 요청 제한이 직접 만들던 Lettuce 클라이언트·연결 빈과 `PING` 상태 확인을 지우고 Spring Boot
  `spring.data.redis` 자동 구성, `StringRedisTemplate`·`RedisScript`, 기본 `redis` 상태 확인으로 바꿨다. 끊긴 연결의
  명령을 쌓지 않는 옵션은 `LettuceClientOptionsBuilderCustomizer`로 유지했다. 기존 환경 변수 이름은 그대로 쓴다.
  Spring Boot가 빈 Redis URL을 시작 단계에서 거부하는 것을 임시 테스트로 확인해 Compose는 형식이 맞는 기본 주소를
  넘긴다. Redis 연결은 첫 사용 때 맺으므로 연결 실패는 시작 대신 `/readyz`·시작 탐침 실패로 드러나며, 운영 ACL에는
  `INFO`·`EVALSHA`가 필요하다. 테스트에서만 쓰던 `TrustedTargetPolicy.isAllowed`도 합쳤다. 운영 코드 120줄을 지우고
  45줄을 더했으며 Spring Data Redis 의존성 검증 항목 14개를 추가했다. 링크 코드 키의 Bean Validation 전환은 실패
  메시지에 거부한 비밀값이 찍혀 하지 않았다. Java 21에서 결과 디렉터리를 비운 뒤 `./gradlew --no-daemon build
  :bootstrap:mysqlTest :bootstrap:redisTest`를 통과했다. 도메인 72·애플리케이션 41·웹 120·외부 20·bootstrap 29·
  MySQL 29·Redis 3개에 실패·제외가 없다. `docker build`와 CI 운영 이미지 기동 단계를 같은 스크립트로 로컬 실행해
  분산 제한을 끈 기본 구성의 준비 상태와 공개 오류 응답을 확인했다. 로그는 스크래치패드의 `smoke/run.log`다.
  원격 반영은 하지 않았다.
- 테스트·인계 정리는 미커밋 변경이 없는 `main`의 `41348ab`에서 시작해 `0da197b`와 문서 커밋에 저장했다.
  읽기 전용 검토 두 건의 후보 중 다른 테스트가 같은 분기를 확인하는 테스트를 지우거나 매개변수 테스트로 합쳤다.
  생성 실패 오류 매핑과 복구 실패 카운터는 HTTP 테스트 하나로 모았고, 같은 설정의 키 교체·보존 정리 MySQL
  테스트는 `LinkLifecycleIntegrationTest`로 합쳐 컨테이너·컨텍스트 기동을 하나 줄였다. 키 확인 테스트는
  `smart_links`만 있는 경우를 따로 확인하도록 고쳤다. 테스트 19개 파일에서 820줄을 지우고 352줄을 더했으며
  운영 코드는 바꾸지 않았다. HANDOFF의 이전 검증 항목은
  [2026-10-05 검증 기록](2026-10-05-verification-history.md)으로 옮기고 원격 반영 여부와 공개 출처
  복구 설명을 현재 사실에 맞췄다. Java 21에서 결과 디렉터리를 비운 뒤 `./gradlew --no-daemon test`,
  `:bootstrap:mysqlTest`, `:adapter-in-web:apiContractDocs`, `build -x test`를 통과했다. 도메인 72·애플리케이션 41·
  웹 120·외부 24·bootstrap 31·MySQL 29개에 실패·제외가 없고 REST Docs 조각은 84개다. 문서 링크 검사는 39개 파일에서
  문제 0건이다. JWK 테스트 서버·실제 서버 요청 헬퍼 공용화는 줄어드는 양이 작고 JWK 캐시 순서에 기대게 돼 하지
  않았다. Redis 테스트·운영 코드·이미지가 바뀌지 않아 Redis 통합 검증과 이미지 빌드는 하지 않았다.
  원격 반영은 하지 않았다.
