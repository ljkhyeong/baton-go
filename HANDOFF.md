# 인수인계

## 현재 상태

- GO의 링크 생성·조회·접속 처리·폐기와 동일 요청 재시도는 구현되어 있다.
  동작 기준은 [제품 명세](docs/PRD/0001_product-baseline/spec.md),
  [API 계약](docs/PRD/0002_api-contract/spec.md),
  [BATON·ROUND 연동 계약](docs/PRD/0003_cross-service-link-contract/spec.md)이다.
  GO 링크는 위치만 제공하며 접근 권한은 BATON·ROUND가 판단한다.
- 외부 연동은 [코드·연동 준비](docs/RUNBOOK/external-api-integrations.md#코드연동-준비)를 따른다.
  운영 계정·홈서버·공유기·이미지 빌드·k3s 설정은 사용자가 진행한다. 애플리케이션 환경변수와
  Slack·Discord·Healthchecks URL 예시, 외부 통신 없는 웹훅 전달 검증 도구·CI 단계를 준비했다.
  Discord 예시는 `wait=true`를 사용한다. 실제 인증 주소·비밀값·웹훅 URL 연결은 남아 있다.
  로컬 복사본 `deploy/app.env`와 웹훅 URL 파일 3개는 Git·Docker 빌드 전송 대상에서 제외한다.
  현재 체크아웃에는 예시와 같은 임시값을 권한 `0600`으로 복사했다. 실제 운영 값은 입력하지 않았다.
- 관리 API의 잘못된 쿼리·경로·JSON 본문 값과 필수 쿼리 누락 오류에 문제 항목을 표시한다.
  Spring·Jackson의 기존 입력 변환·검증을 사용하며 원문 값은 오류 응답에 넣지 않는다.
  깨진 JSON과 등록되지 않은 항목은 공통 형식 오류로 안내한다.
  HTTP 상태·오류 코드·JSON 구조와 서비스 호출 전 거부 시점은 유지한다.
- 공개 링크의 활성 전·요청 제한·일시적 서버 오류 화면에 `다시 열기` 버튼을 추가했다.
  누를 때만 같은 주소를 다시 요청한다. 만료·폐기·없는 링크에는 표시하지 않으며,
  기존 JSON·HEAD 응답과 요청 제한·보안 헤더를 유지한다.
  활성 전 화면에는 저장된 시작 시각을 한국 시간으로 표시한다. 실제 활성 판정은 UTC 기준을 유지한다.
- 관리 링크 목록에 `expiresFrom`·`expiresBefore`를 추가했다. `status=ACTIVE`와 함께 지정하면
  곧 만료될 링크를 찾을 수 있다. 시작 시각은 포함하고 끝 시각과 무기한 링크는 제외한다.
  기존 생성 기간·대상·상태 필터, 조회 한도와 빈 페이지의 다음 커서는 유지한다.
- 종료 링크 자동 삭제와 Redis 분산 요청 제한은 기본 중지 상태다. 실제 운영 환경 검증과 배포는 남아 있다.
- 보존할 기존 GO DB가 없어 과거 배포 호환 기능을 제거했다. 대문자·nil·버전 6 이상 UUID와 나노초 시각의
  기존 예약 재시도 경로, 기존 DB용 `guard-tool`, 대상 계약 v1 점검·폐기 운영 API·scope·설정이 없다.
  공개 조회·관리 API에서 계약 위반 저장 대상을 404로 숨기는 방어와 재시도 저장값 누락 오류는 유지한다.
- 폐기를 처리한 서버의 시계가 생성 시각보다 늦으면 500 대신 폐기 시각을 생성 시각으로 맞춘다.
- Spring Boot 4.1.1이 관리하는 Tomcat·Jackson은 루트 `build.gradle`의 해석 규칙으로 보안 수정본인 Tomcat 11.0.26·
  Jackson 3.1.7을 쓴다. 이 버전을 포함한 Boot 패치가 나오면 Boot를 올리고 규칙을 지운다.
- 영속성은 JPA 없이 Spring `JdbcClient`로만 처리한다. 첫 운영 배포 전이라 마이그레이션을 최종 스키마의
  단일 V1으로 다시 만들었고 `link_code_key_guard` 보호 행과 `version` 열이 없다. 그 이전에 만든 로컬
  MySQL 볼륨은 [README 안내](README.md#로컬-실행)에 따라 다시 만든다.
- 관리 보안 필터의 401·403·인증 서비스 500은 `HandlerExceptionResolver`를 거쳐 `GlobalExceptionHandler`가
  응답한다. 공개 링크 오류는 같은 advice 안의 JSON·HTML 처리기 두 개로 협상한다.
- `BATON_GO_LINK_CODE_SECRET` 단일 비밀값의 키 ID는 `default`다. 키 교체 시 `BATONGO_LINKCODE_KEYS_DEFAULT`로 옮긴다.
- `go.b4ton.com` 운영 예시와 Cloudflare DNS API 인증서 갱신·Discord·Slack 웹훅 알림의 선택 설정을
  추가했다. [외부 API 연동 절차](docs/RUNBOOK/external-api-integrations.md)를 따르며
  서버 설치, 실제 토큰 연결, DNS 변경, 인증서 발급과 메시지 전송은 실행하지 않았다.
- [추가 연동 검토](docs/RUNBOOK/external-api-integrations.md#추가-검토-결과)에서 Workers KV의 링크 저장,
  Cloudflare 요청 제한 대체·Turnstile, GitHub Dependency Review는 현재 계약·비용 조건상 제외했다.
  제외 이유와 재검토 조건을 기록했다. 계정·채널 연결과 실제 수신 확인은 사용자가 진행한다.
- Cloudflare의 무료 [인증서 발급 알림](docs/RUNBOOK/external-api-integrations.md#cloudflare-인증서-발급-알림)
  설정·확인·해제 절차를 추가했다. 애플리케이션 코드나 주기 실행은 추가하지 않았다.
  계정 연결이 없어 실제 활성화·수신자 등록·이메일 수신은 확인하지 않았다.
- Dependabot의 CI 액션·Java 21 기반 이미지 업데이트 제안 설정과 GitHub 공식 Slack 앱의 CI 구독 절차를 추가했다.
  Java 이미지는 사용하지 않는 빌드 인자를 제거하고 Dockerfile의 `FROM`에 직접 고정했다.
  Java 메이저 업데이트와 MySQL 이미지는 자동 제안에서 제외한다.
  원격 `main`에 반영했으며 실제 채널 연결은 남아 있다.
- GitHub 저장소의 Dependabot alerts를 활성화하고 조회로 확인했다. 자동 수정 PR은 중지 상태다.
  기존 Trivy 결과의 Java 패키지 목록을 `main` CI 성공 후 의존성 API로 제출하는 설정을 추가했다.
  `8ab710f`의 원격 CI에서 Java 목록 제출 단계가 성공했다.
- `main`의 빌드·이미지·실행·DB 검증 후 같은 이미지를 GHCR에 게시하는 CI 설정을 추가했다.
  게시 다이제스트·검사 메타데이터를 별도 산출물에 기록하며 재빌드하지 않는다.
  [게시·배포 참조 절차](docs/RUNBOOK/image-security-reports.md#ghcr-자동-게시와-배포-참조)를 따르며,
  `8ab710f`의 원격 CI에서 GHCR 게시·다이제스트 보존 단계가 성공했다. 홈서버 이미지 수신은 미확인이다.
- 게시 다이제스트에 SLSA 빌드 출처·CycloneDX SBOM 증명을 만들고 배포 기준으로 검증하는 CI 작업과
  릴리스 보관 시 증명 재검증을 `64e4d75`에 추가했다. 공개 저장소라 서명 묶음은 Sigstore 공개 투명성
  로그에 기록된다. `a20146e`의 원격 CI에서 증명 생성과 배포 기준 검증이 성공했다.
  릴리스 보관 워크플로의 재검증과 [배포 전 확인](docs/RUNBOOK/image-security-reports.md#빌드-출처sbom-증명)
  명령의 홈서버 실행은 남아 있다.
- 기존 태그와 같은 커밋의 성공한 `main` CI 자료를 GitHub Release 초안에 첨부하는
  [보관 워크플로](.github/workflows/release-evidence.yml)를 추가했다. 검사·게시 자료의 일치와
  필수 파일을 확인하며 재빌드하지 않는다. 실제 태그 등록·Release 생성·발행은 하지 않았다.
  실패한 CI 작업만 재실행한 경우 이전 시도의 정상 이미지 자료도 사용한다. 초안에는 최종 CI
  성공 시도와 이미지 게시 시도를 각각 기록한다.
- GitHub의 [릴리스 변경 방지](docs/RUNBOOK/image-security-reports.md#릴리스-변경-방지)를
  `ljkhyeong/baton-go`에 활성화하고 API의 `enabled: true`를 확인했다.
  이후 발행본의 첨부 파일과 태그를 잠그며 초안 생성·검토 절차는 유지한다. 현재 Release는 없다.
- [릴리스 취약점 재검사](.github/workflows/release-vulnerability-review.yml)는 보관한 SBOM을
  최신 Trivy DB로 대조하는 수동 워크플로다. 태그·게시 기록·SBOM의 이미지 일치를 확인하고
  재검사 보고서를 14일간 보관한다. 원본 Release·이미지·배포는 변경하지 않는다.
  실제 CI 자료로 로컬 재검사를 확인했으며 원격 워크플로 실행은 남아 있다.
- kube-state-metrics의 GO 상태 수집·최소 조회 권한과 Pod 준비 실패·반복 재시작·가용 Pod 부족·
  마이그레이션 실패·지표 누락 경보를 추가했다. 기존 Slack·Discord 경로로 전달한다.
  [연결 절차](docs/RUNBOOK/prometheus-alerts.md#컨테이너배포-실패-알림)는 k3s 구축 후 적용할 설정이며
  실제 수집기 설치·클러스터 적용·알림 전송은 하지 않았다. 외부 백업은 저장 위치 확정이 남아 있다.
- HetrixTools의 공개 HTTPS 감시 등록 예시와 Healthchecks.io 주기 신호 설정을 추가했다.
  기존 공개 오류 경로의 404·본문을 확인하고, 감시 시스템은 고정 본문만 외부로 보낸다.
  HetrixTools는 같은 Contact List로 도메인 만료 15일 전·네임서버 변경도 알리도록 설정했다.
  [외부 감시 절차](docs/RUNBOOK/external-availability-monitoring.md)에 무료 조건과 Slack 연결을 정리했다.
  실제 계정·수신 채널 연결과 홈서버 적용은 남아 있다.
- cert-manager 인증서의 준비 실패·갱신 지연·만료 임박·지표 누락 경보와 Discord·Slack 전달을
  추가했다. Cloudflare 자동 갱신을 선택할 때만 수집 job과 경보 파일을 함께 연결한다.
  실제 클러스터 수집·발급·갱신과 알림 수신은 아직 확인하지 않았다.
- 일반 링크 폐기 완료 이력은 최초 요청을 `LINK_REVOKE`, 이미 폐기된 링크의 반복 요청을
  `LINK_REVOKE_REPLAY`로 구분한다. HTTP 응답과 폐기 시각 보존 동작은 바뀌지 않았다.
- 관리 JWT는 공백이 아닌 `sub`를 서비스 식별자로 요구한다. 누락·빈 문자열·공백 문자열은
  관리 작업 전에 `401 MANAGEMENT_AUTHENTICATION_REQUIRED`로 거부한다.
- 링크 생성 예약은 일반 `INSERT`를 사용하며 Spring이 변환한 중복 키 오류만 기존 예약 조회로
  처리한다. 다른 MySQL 저장 오류는 성공이나 재시도로 바꾸지 않는다.
- 멱등 예약의 공개 출처는 정리 전까지 DB 검사 제약으로 항상 저장된다. 저장값이 정규 출처 형식이 아닐 때만
  `PUBLIC_LINK_ORIGIN_REPLAY_UNAVAILABLE`로 처리하고 그 밖의 실행 오류는 복구 오류로 바꾸지 않는다.
- 종료 링크 정리와 Redis 분산 요청 제한의 수치·시간 범위는 Spring `@ConfigurationProperties`
  검증을 사용한다. 기능 활성화에 따른 필수값 조건만 생성자에서 확인한다.
- 종료 링크 자동 정리 실행 간격은 최소 1초다. 빈 값과 `0s`는 설정 바인딩 단계에서 거부한다.
- 자동 정리를 켜면 Pod별 실행 완료 시각과 설정 간격을 지표로 제공한다. 완료 신호가 실행 간격의
  3배(최소 3분)를 넘긴 상태로 2분 지속되면 정체 경보가 발생한다.
- 준비 상태는 DB와 활성화한 분산 요청 제한 Redis를 확인한다. Redis는 Spring Boot 자동 구성과 기본 상태 확인
  (`INFO`)을 사용하며, 실패하면 `/readyz`는 503이고 `/livez`는 정상 상태를 유지한다.
- `BatonGoReadinessFailed`는 Pod별 `/readyz` 요청이 최근 2분간 12건 이상이고 503 비율이
  50%를 넘는 상태가 2분 지속되면 발생한다.
- 애플리케이션은 종료 신호를 받으면 새 요청 수락을 멈추고 진행 중인 요청을 최대 30초 기다린다.
  Kubernetes와 Compose의 강제 종료 제한은 40초다.
- 롤링 업데이트는 가용 Pod를 줄이지 않고 새 Pod 한 개만 추가한다. 새 Pod는 준비 상태를 10초간
  유지한 뒤 가용 상태로 인정한다.
- BATON 연동 작업 공간은 `/private/tmp/baton-go-integration-20260905`, 브랜치는
  `codex/go-link-integration-20260905`다. 최근 확인 리비전은 `90557822`, 코드 기준은 `bc888b15`다.
  GO 연동·카카오톡 공유·QR 기능을 반영했으며 BATON 메인 병합과 실제 전송·스캔 검증은 남아 있다.
- 완료 내역과 이전 검증의 리비전·명령·로그는
  [구현·검증 기록](docs/HISTORY/2026-09-08-implementation-verification.md)과
  [2026-10-05 검증 기록](docs/HISTORY/2026-10-05-verification-history.md)에 보관한다.

## 최근 검증

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
  [2026-10-05 검증 기록](docs/HISTORY/2026-10-05-verification-history.md)으로 옮기고 원격 반영 여부와 공개 출처
  복구 설명을 현재 사실에 맞췄다. Java 21에서 결과 디렉터리를 비운 뒤 `./gradlew --no-daemon test`,
  `:bootstrap:mysqlTest`, `:adapter-in-web:apiContractDocs`, `build -x test`를 통과했다. 도메인 72·애플리케이션 41·
  웹 120·외부 24·bootstrap 31·MySQL 29개에 실패·제외가 없고 REST Docs 조각은 84개다. 문서 링크 검사는 39개 파일에서
  문제 0건이다. JWK 테스트 서버·실제 서버 요청 헬퍼 공용화는 줄어드는 양이 작고 JWK 캐시 순서에 기대게 돼 하지
  않았다. Redis 테스트·운영 코드·이미지가 바뀌지 않아 Redis 통합 검증과 이미지 빌드는 하지 않았다.
  원격 반영은 하지 않았다.
- 3차 정리는 미커밋 변경이 없는 `main`의 `d9aa3a7`에서 시작해 `f334005`·`a5d39a9`·`90e7935`·`fc2dfde`·`e6e511d`와
  문서 커밋에 저장했다. 공개 링크 오류 처리기 쌍 12개를 협상 매핑 2개와 예외 패턴 분기로 합치고, 도달하지 않는
  대상 정책 null 검증과 서비스 매개변수 풀기를 정리했다. 단일 비밀값 키 ID를 `legacy`에서 `default`로 바꾸고 키 확인
  통합 테스트를 다른 키 테스트 패키지로 옮겼다. 운영 코드 155줄을 지우고 45줄을 더했다. Gradle 권장 방식대로 기존
  파일을 치운 오프라인 `--write-verification-metadata sha256 --dry-run` 결과와 대조해 JPA 계열 검증 항목 34개를
  지웠다. 처음 48개를 지운 시도는 캐시 없는 이미지 빌드가 플러그인 경로의 `tools.jackson:jackson-base:3.1.5` POM을
  요구해 실패했고, 공통 BOM·부모 POM을 남기는 방식으로 바꿨다. `--refresh-dependencies build`와 `docker build --no-cache`
  가 검증을 통과했다. Java 21에서 결과 디렉터리를 비운 뒤 `./gradlew --no-daemon build :adapter-in-web:apiContractDocs
  :bootstrap:mysqlTest :bootstrap:redisTest`를 통과했다. 도메인 73·애플리케이션 42·웹 124·외부 25·bootstrap 31·
  MySQL 29·Redis 3개에 실패·제외가 없고 REST Docs 조각은 84개다. 최종 트리의 `docker build`도 성공했다.
  로그는 같은 스크래치패드의 `r3-*.log`·`verif-*.log`다. 생성·조회 사용 사례 분리, 관리 목록 쿼리 객체 바인딩,
  JWK 테스트 서버 공용화와 Spring Data Redis 전환은 이점보다 변경 범위가 커 하지 않았다. 이후 원격 `main`에 반영했고
  `41348ab`의 [원격 CI](https://github.com/ljkhyeong/baton-go/actions/runs/37260550380)가 성공했다.
- 2차 과감한 정리는 미커밋 변경이 없는 `main`의 `48bb23e`에서 시작해 `9dab25f`·`772a3bb`·`9eeb4da`·`f4eda73`·
  `fea3c98`에 저장했다. 운영 데이터와 적용된 DB가 없다는 사용자 확인에 따라 JPA를 제거해 영속성을
  `JdbcClient`로 통일하고, V1~V7을 최종 스키마의 V1 하나로 다시 만들었다. 보호 행·`version` 열·쓰이지 않던
  만료 인덱스를 없애고 정리 전 예약의 공개 출처를 검사 제약으로 강제했다. 저장 링크 레코드 3종을 `StoredLink`로
  합치고 `FilterErrorResponseWriter`를 지워 보안 필터 오류를 MVC 예외 처리기로 모았다. 운영 코드 603줄을 지우고
  196줄을 더했으며 테스트는 504줄을 지우고 103줄을 더했다.
  Java 21에서 결과 디렉터리를 비운 뒤 `./gradlew --no-daemon build :adapter-in-web:apiContractDocs
  :bootstrap:mysqlTest :bootstrap:redisTest`를 통과했다. 도메인 73·애플리케이션 42·웹 124·외부 25·bootstrap 31·
  MySQL 29·Redis 3개에 실패·제외가 없고 REST Docs 조각은 84개다. `docker build`와 CI의 운영 이미지 기동 단계를
  같은 스크립트로 로컬 실행해 통과했다. 실행 클래스패스에 Hibernate ORM·Spring Data JPA가 없음을 확인했다.
  MockMvc는 HEAD 본문을 버리지 않아 관리 HEAD 403의 빈 본문 확인을 실제 HTTP 서버 테스트로 옮겼다.
  로그는 같은 스크래치패드의 `bold-*.log`와 `smoke/run.log`다. Gradle 검증 메타데이터의 쓰이지 않는 JPA 항목은
  다시 생성하려면 네트워크 조회가 필요해 남겼고 3차 정리에서 지웠다. 이후 원격 `main`에 반영했고
  `41348ab`의 [원격 CI](https://github.com/ljkhyeong/baton-go/actions/runs/37260550380)가 성공했다.
- 과거 호환 정리는 미커밋 변경이 없는 `main`의 `bfb9a14`에서 시작해 `7c1c0a0`·`274fb64`·`110abfd`·`c950b5d`·
  `c3e2e0b`·`c3040ae`에 저장했다. 보존할 기존 GO DB가 없다는 사용자 확인에 따라 과거 형식 멱등 재시도,
  `guard-tool` 모듈·CI 단계, 대상 계약 운영 API와 점검 전용 예약 존재 조회를 제거했다. 예시에서 사라진 공개
  비밀값 차단, 내부 키 지문 형식 검사, 설정과 겹친 발급 키 검사도 지웠다. 폐기 시각 검사의 500은
  `274fb64`에서 생성 시각 보정으로 고쳤다. 운영 코드 818줄, 테스트 1,431줄, 문서·스킬 442줄을 줄였다.
  Java 21에서 `./gradlew --no-daemon build :adapter-in-web:apiContractDocs :bootstrap:mysqlTest
  :bootstrap:redisTest`를 통과했다. 도메인 73·애플리케이션 42·웹 123·외부 25·bootstrap 31·MySQL 32·Redis 3개에
  실패·제외가 없고 REST Docs 조각은 84개다. `docker build`도 성공했다. 단계마다 대상 테스트를 먼저 실행했으며,
  3단계에서 guard-tool의 과거 대문자 검증용 요청 테스트가 실패해 같은 단계에서 제거했다. CI 변경은
  actionlint 1.7.12, 설정 변경은 Compose·Kustomize 렌더, 문서는 전체 링크 검사로 확인했다. 로그는 같은
  스크래치패드의 `cleanup-*.log`다. 이후 원격 `main`에 반영했고
  `41348ab`의 [원격 CI](https://github.com/ljkhyeong/baton-go/actions/runs/37260550380)가 성공했다.
- 정리·커서 리팩터링은 미커밋 변경이 없는 `main`의 `6c09109`에서 시작해 `6af1a72`·`07ffbb8`에 저장했다.
  영속성 어댑터가 계산하던 정리 요청 해시를 `LinkRetentionService`로 옮겨 어댑터는 잠금 조회와 정리 SQL만 맡고,
  `LinkCreationFingerprint`는 애플리케이션 패키지 안으로 숨겼다. 관리 목록과 대상 계약 점검에 중복된
  `limit + 1`건 조회·최대 500건·다음 커서 규칙은 `StoredLinkScan`으로 모았다. SQL·잠금·오류 코드·응답은 같다.
  Java 21에서 `./gradlew --no-daemon test`와 `:bootstrap:mysqlTest --tests '*LinkRetentionIntegrationTest'
  --tests '*TargetContractOperationsIntegrationTest' --tests '*LinkPersistenceIntegrationTest'`를 통과했다.
  도메인 72·애플리케이션 48·웹 135·외부 26·guard-tool 4·bootstrap 32·MySQL 21개 테스트에 실패·제외가 없고
  계층 규칙 5개도 통과했다. 스케줄러 테스트는 포트 대신 서비스를 목으로 쓰고, 정리 통합 테스트는 실제 서비스를
  거쳐 정리한다. 로그는 같은 스크래치패드의 `refactor-*.log`다. 키 가드 어댑터의 키 묶음 판정은 잠금 순서와
  얽혀 있어 이번 범위에서 제외했다. Redis·배포 구성은 바뀌지 않아 Redis 통합 검증과 이미지 빌드는 하지 않았다.
- 이미지 증명 추가는 미커밋 변경이 없는 `main`의 `0983bf2`에서 시작해 워크플로를 `64e4d75`에 저장했다.
  다음 작업 6의 서명·빌드 출처 검증을 위해 `actions/attest` v4.2.2(`1e69f48`)로 증명을 만드는
  `image-attestation` 작업과 보관 워크플로의 재검증을 추가했다. `id-token`·`attestations: write`는 새 작업에만 준다.
  actionlint 1.7.12(ShellCheck 0.11.0)로 워크플로 3개를 통과했다. 추출한 실제 단계 스크립트를 Ubuntu 24.04·
  bash 5.2·jq 1.7과 대체 `gh`·`docker`·`git`으로 실행해 증명 대상 9건(정상·대문자 저장소·커밋/실행/검사
  불일치·다른 저장소·다이제스트 형식), CI 검증 3건, 보관 4건의 16개 시나리오를 통과했다. 첫 실행의 4건 실패는
  대체 도구의 기록 형식과 시나리오 실행 주소의 대소문자 오류였다. `gh` 2.86.0의 검증 플래그는 공개
  `ghcr.io/actions/actions-runner` 증명에서 일치 시 통과, 소스 커밋·ref·서명 워크플로 불일치 시 거부를 확인했다.
  CI와 같은 Trivy 0.72.0 `convert`의 CycloneDX가 `actions/attest`의 판별 필드를 갖는 것도 네트워크 없이 확인했다.
  변경 문서 4개의 링크·앵커 문제는 0건이고 배포 전 확인 명령은 Bash 구문·ShellCheck를 통과했다. 도구와 로그는
  스크래치패드 `9beb8bea-0936-4cc1-98ce-24ef4a8084ad/scratchpad/attestation-validation`의 `run-scenarios.sh`·
  `scenarios.log`·`actionlint-attestation.log`다. Java·DB·이미지가 바뀌지 않아 Gradle·MySQL·Redis 검증과
  이미지 빌드는 하지 않았다. 이후 원격 `main`에 반영했고 `a20146e`의
  [원격 CI](https://github.com/ljkhyeong/baton-go/actions/runs/37253336070)는 필수 검증·의존성 제출·출처 증명
  세 작업이 모두 성공했다. 증명 작업의 생성·배포 기준 검증 단계도 성공했고, 증명 API에서 게시 다이제스트
  `sha256:4e240e27…`의 SLSA·CycloneDX 증명 2개를 조회했다. 기록 커밋 `6c09109`의 원격 CI도 세 작업이 모두
  성공했다. 로컬 GHCR 인증이 필요한 배포 전 확인 명령과 Release 생성은 실행하지 않았다.

## 다음 작업

1. 실제 릴리스 이미지에서 BATON·ROUND 인증, 공개 HTTPS와 외부 coturn을 연결한다.
   세션·CSRF·쿠키·JWK 교체·TURN·WebSocket 검증 결과를 기록한다. GO 관리 API는
   Spring Security JWT와 작업별 scope로 전환했으므로 실제 발급자 식별자·JWK, 서비스 신원,
   audience·scope와 키 교체를 비공개 네트워크에서 검증한다.
2. 최신 BATON 변경과 GO 연동 브랜치를 병합하고 충돌한 동작을 검증한다.
   GO 연동과 이후 BATON·공휴일 기능을 함께 유지한다. 아직 배포하지 않은 GO 마이그레이션 V40~V42는
   계정 비활성화 V39 다음 순서이며, 운영 DB에 적용한 파일은 교체하지 않는다.
   BATON 연동 브랜치의 GO 검증 고정 커밋은 원격 `main`의 `bf93dc3`이다. 이후 GO에서 과거 형식 멱등성 키와
   대상 계약 운영 API를 제거하고 마이그레이션 기준선을 다시 만들었으므로 고정 커밋을 올릴 때 새 GO DB로
   BATON의 GO 계약 검증을 다시 실행한다. BATON Actions의
   `BATON_GO_CONTRACT_READ_TOKEN` 등록을 확인한 뒤 GitHub 품질 게이트를 실행한다.
   실제 서비스 JWT와 HTTPS 환경에서 생성 응답 유실 후 취소·폐기까지 재시도되는지 확인한다.
   상태 확인 장애 경보와 링크 생성·폐기·상태 일괄 조회도 검증한다.
   상태 확인·링크 생성 요청 저장·GO API 호출은 기본 중지하며 기존 방의 링크 일괄 생성은 포함하지 않는다.
   카카오 앱의 무료 사용 설정·실제 메시지 전송과 실기기 QR 스캔을 확인한다.
3. 외부 프록시에서 공개 `/l`과 비공개 `/api/v1` 라우팅을 분리하고, 분산 요청률 제한과
   `/l/{code}` 접근 로그 마스킹을 적용한다. 분산 제한 구현은 준비되었으며 실제 Redis·Ingress 검증은 남아 있다.
4. 실제 비공개 클러스터에서 DNS·TLS·CNI·NetworkPolicy·startup/liveness/readiness probe와
   PVC·Secret의 생성·교체·복구·삭제를 검증한다. 현재 ingress 전용 NetworkPolicy에 더해 환경별 DNS·MySQL
   egress 허용 목록과 기본 차단 정책을 정하고 실제 CNI의 허용·차단 결과를 기록한다.
   `restricted` 정책 강제 적용 전에는 고정 MySQL 이미지로 신규·복원·현재 PVC의 기동,
   TLS·초기화·마이그레이션을 검증한다.
5. 첫 릴리스 태그에서 보관 워크플로의 증명 재검증을 실행한다. 배포 전 확인 명령으로 승인한
   다이제스트만 배포되는지 검증하고 결과를 배포 기록에 남긴다.
6. Prometheus 수집, 경보 규칙, 알림 경로와 담당자를 연결하고 마이그레이션 실패,
   Pod 비정상, 5xx·429, DB 준비 상태, 저장 대상 계약 위반, PVC 용량과 백업 실패 경보의
   시험 결과를 기록한다.
7. 재시도 시 기존 URL 반환 보장 기간과 만료·폐기 링크 보존 기간, 자동 정리 뒤 HTTP 의미, 백업·감사
   보존과 PVC 경보·증설 기준을 함께 결정한다. 정리 구현은 준비되어 있으며 이 결정 전에는
   자동 정리를 활성화하지 않는다.
8. 플랫폼 백업 정책에 RPO·RTO·주기·보존 기간·담당자·실패 경보·결과 보관 위치를 명시하고,
   DB와 같은 버전의 `BATON_GO_LINK_CODE_SECRET`을 한 복구 단위로 사용한 격리 복원,
   장애 대응과 이미지 되돌리기 훈련을 완료한다.
9. HMAC 비밀값 유출 시 Secret만 바꾸지 말고 링크 생성과 공개 경로를 먼저 차단한다.
   기존 링크 폐기·재발급과 키 목록 전환을 함께 수행하는 복구 절차를 정하고 훈련한다.
   평상시 키 교체도 [운영 절차](docs/RUNBOOK/link-code-key-rotation.md)에 따라 배포·전환·복원을 검증한다.

운영·복구 절차는
[비공개 Kubernetes 배포 절차](docs/RUNBOOK/kubernetes-private-server-deployment.md)와
[발급 키 버전 결정](docs/ADR/0011_link-code-key-ring/adr.md)을 따른다.
