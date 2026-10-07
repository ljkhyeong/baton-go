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
- 영속성은 JPA 없이 Spring `JdbcClient`로만 처리한다. 첫 운영 배포 전이라 기준선 V1을 다시 만들었고
  `link_code_key_guard` 보호 행과 `version` 열이 없다. V2는 생성 예약 때 `request_hash`를 필수로 저장한다.
  정리 전 예약이 남은 DB에는 V2를 적용할 수 없고 V1까지만 아는 이미지로 되돌리면 새 링크를 만들 수 없다.
  이전에 만든 로컬 MySQL 볼륨은 [README 안내](README.md#로컬-실행)에 따라 다시 만들고, 운영 조건은
  [되돌리기·마이그레이션 실패 복구](docs/RUNBOOK/kubernetes-private-server-deployment.md#7-업데이트와-되돌리기)를 따른다.
- 관리 보안 필터의 401·403·인증 서비스 500은 `HandlerExceptionResolver`를 거쳐 `GlobalExceptionHandler`가
  응답한다. 프로젝트 예외는 처리기 하나의 예외 유형 switch로, Spring MVC 예외는 `ResponseEntityExceptionHandler`
  재정의로 처리한다. 공개 링크 오류는 같은 advice 안의 JSON·HTML 처리기 두 개로 협상한다.
  예상하지 못한 오류 로그는 Boot `StandardStackTracePrinter`로 예외 유형·호출 위치만 한 줄에 남기며,
  요청 ID는 MDC에서 읽는다.
- 기본 링크 코드 키는 `BATONGO_LINKCODE_KEYS_DEFAULT`(키 ID `default`)이며 키 교체는 같은 형식으로 키 ID를 추가한다.
  로컬 무시 파일 `deploy/app.env`의 옛 `BATON_GO_LINK_CODE_SECRET` 이름은 사용자가 직접 바꿔야 한다.
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
- 애플리케이션은 Spring Boot 기본 정상 종료로 종료 신호를 받으면 새 요청 수락을 멈추고 진행 중인 요청을
  최대 30초 기다린다. Kubernetes와 Compose의 강제 종료 제한은 40초다.
- 관리 포트 8081은 상태 확인과 `/actuator/prometheus`만 노출한다.
- 롤링 업데이트는 가용 Pod를 줄이지 않고 새 Pod 한 개만 추가한다. 새 Pod는 준비 상태를 10초간
  유지한 뒤 가용 상태로 인정한다.
- BATON 연동 작업 공간은 `/private/tmp/baton-go-integration-20260905`, 브랜치는
  `codex/go-link-integration-20260905`다. 최근 확인 리비전은 `90557822`, 코드 기준은 `bc888b15`다.
  GO 연동·카카오톡 공유·QR 기능을 반영했으며 BATON 메인 병합과 실제 전송·스캔 검증은 남아 있다.
- 완료 내역과 이전 검증의 리비전·명령·로그는
  [구현·검증 기록](docs/HISTORY/2026-09-08-implementation-verification.md),
  [2026-10-05 검증 기록](docs/HISTORY/2026-10-05-verification-history.md)과
  [2026-10-06 검증 기록](docs/HISTORY/2026-10-06-verification-history.md)에 보관한다.

## 최근 검증

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
  [2026-10-06 검증 기록](docs/HISTORY/2026-10-06-verification-history.md)으로 옮겼다. 원격 반영은 하지 않았다.

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

## 다음 작업

1. 실제 릴리스 이미지에서 BATON·ROUND 인증, 공개 HTTPS와 외부 coturn을 연결한다.
   세션·CSRF·쿠키·JWK 교체·TURN·WebSocket 검증 결과를 기록한다. GO 관리 API는
   Spring Security JWT와 작업별 scope로 전환했으므로 실제 발급자 식별자·JWK, 서비스 신원,
   audience·scope와 키 교체를 비공개 네트워크에서 검증한다.
2. 최신 BATON 변경과 GO 연동 브랜치를 병합하고 충돌한 동작을 검증한다.
   GO 연동과 이후 BATON·공휴일 기능을 함께 유지한다. 아직 배포하지 않은 GO 마이그레이션 V40~V42는
   계정 비활성화 V39 다음 순서이며, 운영 DB에 적용한 파일은 교체하지 않는다.
   BATON 연동 브랜치의 GO 검증 고정 커밋은 원격 `main`의 `bf93dc3`이다. 이후 GO에서 과거 형식 멱등성 키와
   대상 계약 운영 API를 제거하고 마이그레이션 기준선을 다시 만들었으며 V2를 추가하고
   기본 키 변수를 `BATONGO_LINKCODE_KEYS_DEFAULT`로 바꿨다. 고정 커밋을 올릴 때 새 GO DB와 바뀐 변수로
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
   DB와 같은 버전의 `baton-go-link-code-key-ring` 키 묶음을 한 복구 단위로 사용한 격리 복원,
   장애 대응과 이미지 되돌리기 훈련을 완료한다.
9. HMAC 비밀값 유출 시 Secret만 바꾸지 말고 링크 생성과 공개 경로를 먼저 차단한다.
   기존 링크 폐기·재발급과 키 목록 전환을 함께 수행하는 복구 절차를 정하고 훈련한다.
   평상시 키 교체도 [운영 절차](docs/RUNBOOK/link-code-key-rotation.md)에 따라 배포·전환·복원을 검증한다.

운영·복구 절차는
[비공개 Kubernetes 배포 절차](docs/RUNBOOK/kubernetes-private-server-deployment.md)와
[발급 키 버전 결정](docs/ADR/0011_link-code-key-ring/adr.md)을 따른다.
