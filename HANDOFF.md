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
- `/l` 아래에서 코드 한 구간이 아닌 경로(빈 코드·끝 슬래시·추가 경로 구간)는 DB 조회 없이 없는 링크 화면과
  `404 LINK_NOT_FOUND`로 응답하며 요청 제한 허용량을 쓰지 않는다. 429 화면 제목은 전체 한도임을 반영해
  "지금은 접속이 많아 링크를 열 수 없습니다."로 안내하고 JSON 메시지는 유지한다. HTTP 방화벽이 거부한
  공개·관리 요청은 Boot 기본 오류 화면 대신 공통 JSON `400 INVALID_REQUEST`로 응답한다.
  폼·파일 업로드를 쓰지 않아 Boot의 폼 필터와 multipart 파서를 껐다. 인증·요청 제한보다 먼저 본문을 읽던 경로가
  없어졌다. HTTP 서버가 먼저 거부하는 잘못된 퍼센트 인코딩·긴 헤더는 공통 형식 밖의 서버 기본 `400`이며 PRD-0002에 적었다.
- 관리 링크 목록에 `expiresFrom`·`expiresBefore`를 추가했다. `status=ACTIVE`와 함께 지정하면
  곧 만료될 링크를 찾을 수 있다. 시작 시각은 포함하고 끝 시각과 무기한 링크는 제외한다.
  기존 생성 기간·대상·상태 필터, 조회 한도와 빈 페이지의 다음 커서는 유지한다.
- 종료 링크 자동 삭제와 Redis 분산 요청 제한은 기본 중지 상태다. 실제 운영 환경 검증과 배포는 남아 있다.
- 보존할 기존 GO DB가 없어 과거 배포 호환 기능을 제거했다. 대문자·nil·버전 6 이상 UUID와 나노초 시각의
  기존 예약 재시도 경로, 기존 DB용 `guard-tool`, 대상 계약 v1 점검·폐기 운영 API·scope·설정이 없다.
  공개 조회·관리 API에서 계약 위반 저장 대상을 404로 숨기는 방어와 재시도 저장값 누락 오류는 유지한다.
- 폐기를 처리한 서버의 시계가 생성 시각보다 늦으면 500 대신 폐기 시각을 생성 시각으로 맞춘다.
- Spring Boot 4.1.2가 Tomcat 11.0.27·Jackson 3.1.7을 관리해 루트 `build.gradle`의 버전 고정 규칙을 지웠다.
  2026-10-30 기준 GitHub Dependabot 경고 10건은 모두 `fixed`이고 주요 런타임 의존성에 OSV 기준 알려진 취약점이 없다.
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
  유출 대응은 [키 유출 대응 절차](docs/RUNBOOK/link-code-key-compromise.md)를 따르며, ADR-0004의 대응 절은 결정 당시 기록이다.
  로컬 무시 파일 `deploy/app.env`의 옛 `BATON_GO_LINK_CODE_SECRET` 이름은 사용자가 직접 바꿔야 한다.
- `go.b4ton.com` 운영 예시와 Cloudflare DNS API 인증서 갱신·Discord·Slack 웹훅 알림의 선택 설정을
  추가했다. [외부 API 연동 절차](docs/RUNBOOK/external-api-integrations.md)를 따르며
  서버 설치, 실제 토큰 연결, DNS 변경, 인증서 발급과 메시지 전송은 실행하지 않았다.
- [추가 연동 검토](docs/RUNBOOK/external-api-integrations.md#추가-검토-결과)에서 Workers KV의 링크 저장,
  Cloudflare 요청 제한 대체·Turnstile은 현재 계약·비용 조건상 제외했다. GitHub Dependency Review는 저장소
  공개로 무료가 됐지만 PR에서 비교할 대상이 액션뿐이라 제외를 유지한다. 제외 이유와 재검토 조건을 기록했다.
  계정·채널 연결과 실제 수신 확인은 사용자가 진행한다.
- Cloudflare의 무료 [인증서 발급 알림](docs/RUNBOOK/external-api-integrations.md#cloudflare-인증서-발급-알림)
  설정·확인·해제 절차를 추가했다. 애플리케이션 코드나 주기 실행은 추가하지 않았다.
  계정 연결이 없어 실제 활성화·수신자 등록·이메일 수신은 확인하지 않았다.
- Dependabot의 CI 액션·Java 21 기반 이미지 업데이트 제안 설정과 GitHub 공식 Slack 앱의 CI 구독 절차를 추가했다.
  Java 이미지는 사용하지 않는 빌드 인자를 제거하고 Dockerfile의 `FROM`에 직접 고정했다.
  Java 메이저 업데이트와 MySQL 이미지는 자동 제안에서 제외한다.
  원격 `main`에 반영했으며 실제 채널 연결은 남아 있다. 2026-10-16에 액션 6건(`ljkhyeong/baton-go#6`)과
  Temurin(`ljkhyeong/baton-go#4`) 제안을 검토해 병합했다. 2026-10-30에 Temurin `21.0.13+9`와 MySQL `8.4.13`을
  직접 올렸다. Dependabot이 보지 않는 MySQL은 태그가 같아도 재빌드될 수 있어 digest를 주기적으로 대조한다.
- 공개 저장소의 secret scanning·push protection은 2026-10-16 조회 기준 꺼져 있다. 저장소 보안 설정이라
  관리자가 [비밀값 유출 감지](docs/RUNBOOK/external-api-integrations.md#비밀값-유출-감지) 절차로 켠다.
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
- 계약을 위반해 숨긴 저장 대상의 폐기 요청도 행 잠금 안에서 최초 폐기 시각을 기록하고 없는 링크와 같은 404로
  응답한다. 일반 폐기와 같은 완료 이력(`serviceId` 포함)과 계약 위반 지표·로그를 남긴다.
- 공개 조회가 만료·폐기 링크에 410을 반환하면 종료 후 지난 시간을 `baton_go_public_resolver_retired_link_age_seconds`
  (1·7·30·90·180·365일 구간, `status` 태그만)에 기록한다. 보존 기간 결정용이며 경보는 없다.
- 생성 예약이 없는 요청의 `expiresAt`이 이미 지났으면 `400 EXPIRES_AT_ALREADY_PASSED`로 거부한다. 응답을 잃은
  호출자는 만료 뒤에도 이 코드로 링크 없음을 확정한다. PRD-0003 호출자 결과 분류에 이 코드와 `403`·
  `410 LINK_PURGED`·자동 재시도하지 않는 복구 오류, 폐기 응답의 완료·재시도 기준을 추가했다.
- 링크 생성 예약은 일반 `INSERT`를 사용하며 Spring이 변환한 중복 키 오류만 기존 예약 조회로
  처리한다. 다른 MySQL 저장 오류는 성공이나 재시도로 바꾸지 않는다.
- 멱등 예약의 공개 출처는 정리 전까지 DB 검사 제약으로 항상 저장된다. 저장값이 정규 출처 형식이 아닐 때만
  `PUBLIC_LINK_ORIGIN_REPLAY_UNAVAILABLE`로 처리하고 그 밖의 실행 오류는 복구 오류로 바꾸지 않는다.
- 종료 링크 정리와 Redis 분산 요청 제한의 수치·시간 범위는 Spring `@ConfigurationProperties`
  검증을 사용한다. 기능 활성화에 따른 필수값 조건만 생성자에서 확인한다.
- 종료 링크 자동 정리 실행 간격은 최소 1초다. 빈 값과 `0s`는 설정 바인딩 단계에서 거부한다.
- 자동 정리를 켜면 Pod별 실행 완료 시각과 설정 간격을 지표로 제공한다. 완료 신호가 실행 간격의
  3배(최소 3분)를 넘긴 상태로 2분 지속되면 정체 경보가 발생한다.
- 시작 상태(`/startupz`)만 DB와 활성화한 분산 요청 제한 Redis를 확인한다. 준비 상태(`/readyz`)는 Spring Boot
  기본 `readinessState`만 보고 `/livez`도 의존성을 보지 않는다. 연결하지 못한 새 Pod는 시작 탐침을 통과하지
  못하고, 실행 중 DB·Redis 장애에는 Pod가 서비스에 남아 GO가 공개 안내 화면과 공통 오류를 직접 반환한다.
  Redis는 Spring Boot 자동 구성과 기본 상태 확인(`INFO`)을 사용한다.
- `BatonGoDependencyUnavailable`은 Pod별 `baton_go_startup_health_up`(수집할 때마다 시작 상태 묶음을 평가)이
  0인 상태가 2분 지속되면 발생해 트래픽이 없어도 DB·Redis 장애를 알린다.
- 애플리케이션은 Spring Boot 기본 정상 종료로 종료 신호를 받으면 새 요청 수락을 멈추고 진행 중인 요청을
  최대 30초 기다린다. Kubernetes와 Compose의 강제 종료 제한은 40초다.
- 관리 포트 8081은 상태 확인과 `/actuator/prometheus`만 노출한다.
- 롤링 업데이트는 가용 Pod를 줄이지 않고 새 Pod 한 개만 추가한다. 새 Pod는 준비 상태를 10초간
  유지한 뒤 가용 상태로 인정한다.
- BATON 연동 브랜치는 BATON 저장소(`/Users/lim/devProject/personal/manager`)의
  `codex/go-link-integration-20260905`다. 2026-10-16 기준 브랜치 끝은 GO 상태 일괄 조회 전환까지 담은 `b6b50a41`이며
  이전 기록의 확인 리비전 `90557822`와 코드 기준 `bc888b15`를 포함한다.
  GO 연동·카카오톡 공유·QR 기능을 반영했으며 BATON 메인 병합과 실제 전송·스캔 검증은 남아 있다.
  2026-10-16 기준 임시 작업 공간 `/private/tmp/baton-go-integration-20260905`는 지워졌고, BATON 최신 통합 브랜치
  `codex/integration-20261014`에는 이 브랜치가 아직 병합되지 않았다.
- 완료 내역과 이전 검증의 리비전·명령·로그는
  [구현·검증 기록](docs/HISTORY/2026-09-08-implementation-verification.md),
  [2026-10-05 검증 기록](docs/HISTORY/2026-10-05-verification-history.md),
  [2026-10-06 검증 기록](docs/HISTORY/2026-10-06-verification-history.md),
  [2026-10-12 검증 기록](docs/HISTORY/2026-10-12-verification-history.md),
  [2026-10-14 검증 기록](docs/HISTORY/2026-10-14-verification-history.md)과
  [2026-10-30 검증 기록](docs/HISTORY/2026-10-30-verification-history.md)에 보관한다.

## 최근 검증

- 전수검토는 미커밋 변경이 없는 `main`의 `4beb79d`에서 시작해 `e95ed8c`·`1a883c8`·`4949020`·`4e0f962`·`2e324a4`·
  `2c72c58`·`8b8b1ce`·`b94002f`에 저장했다. 추적 파일을 도메인·애플리케이션, 웹, 영속성·외부·부트스트랩, 배포·CI,
  문서 대조, 보안 관통, 테스트 공백 7개 구획으로 나눠 모두 읽었고, 후보는 실측·재현으로 확인한 것만 반영했다.
  - 관리 목록의 `ORDER BY id`가 `BIN_TO_UUID` 별칭을 정렬해 기본 키를 쓰지 못했고, 자동 정리 잠금 쿼리는 종료 링크가
    많으면 예약 표를 먼저 읽었다. 링크 50만 건·종료 40%의 MySQL `EXPLAIN ANALYZE`에서 목록은 전체 행 정렬 126ms가
    101행 0.05ms로, 정리는 전체 행 877ms가 100행 0.5ms로 줄었다. `Handler_read` 카운터로 읽은 행 수를 단언하는 테스트를 추가했다.
  - multipart 헤더만 보낸 공개 GET이 요청 제한 전에 `500`과 ERROR 로그를 만들었고, 잘못된 폼 본문의 PUT은 Boot 기본
    형식 `500`을 반환했다. 폼 필터는 Spring 코드상 인증 전에 본문을 크기 제한 없이 읽는다. 원시 소켓으로 재현한 뒤 두
    파서를 껐다. `%2F` 등을 Tomcat이 먼저 거부하는 응답은 서버 버전이 없는 기본 `400`이라 밸브 코드 대신 PRD에 적었다.
  - 키 묶음에 저장 키가 없거나 예약의 링크가 없을 때 다른 내용의 재사용이 `409` 대신 `500`이 되던 순서를 고쳤다.
  - Spring Boot 4.1.2로 올리며 Tomcat·Jackson 고정 규칙을 지웠다. 검증 메타데이터를 새로 만들어 Boot 관리 범위의 변경만
    있는 것을 확인했다. `ljkhyeong/baton-go#8`은 이전 빌드(`21.0.12.1+1`)라 현재 태그의 `21.0.13+9`로 직접 올렸고 Trivy
    기준 런타임 이미지의 CRITICAL 1건 등 7건이 해소되어 0건이 됐다. MySQL 8.4.13은 HIGH 12건 등 14건이 해소되고 새
    항목은 없으며, 비호환 변경이 없고 8.4.11 데이터 디렉터리를 그대로 연다. 남은 MySQL CRITICAL은 공식 이미지의 `gosu`다.
  - 배포·CI는 preStop 5초, main CI 앞선 실행 취소 방지, 종료된 Pod 경보 제외, `.dockerignore` 하위 경로와 `deploy/*.env*`
    제외, 검증 스크립트 추천을 고쳤다. 문서는 없앤 설정·없는 스키마 검증 전제·대체된 절차, `kubectl diff`에서 멈추던
    업데이트 스크립트, 준비되지 않은 Pod에 막히던 MySQL 재시작, 초안 Release 재검사 안내를 바로잡았다.
  - 서비스가 공개 상태 판정을 호출하는지 보는 테스트가 없어 삭제해도 모두 통과했다. 이 공백을 포함해 scope 교차, 동시
    폐기, 자동 정리 실제 빈 실행, 대상 경로 쿼리·프래그먼트, 응답 시각 매핑, 마이그레이션 시간 제한 등을 추가했다.
    수정을 되돌리는 변이 14건을 모두 테스트가 잡았다.

  적용하지 않은 후보는 다음과 같다.
  - 앱 시작 때 스키마 버전 검사: 새 열을 쓰는 코드를 다음 릴리스에 넣는 규칙으로 대신한다. 마이그레이션 때 다시 판단한다.
  - 시작 검사의 사용 키 조회 인덱스: 50만 건에 105ms라 5초 제한까지 여유가 크다.
  - 미등록 `kid`의 JWK 재조회: 비공개 경로에서만 도달한다. Lettuce 반개방 연결은 다음 작업 3으로 남겼다.
  - 재시도 경로의 `FOR SHARE` 변이는 중복 삽입의 공유 잠금이 같은 역할을 해 구분되지 않아 테스트하지 않았다.
  - `AGENTS.md`의 `adapter-out-external` 설명에 Redis 제한이 빠졌지만 지침 파일은 사용자 확인 없이 바꾸지 않았다.

  Java 21에서 `./gradlew --no-daemon --refresh-dependencies build :adapter-in-web:apiContractDocs :bootstrap:mysqlTest
  :bootstrap:redisTest`를 통과했다. 도메인 78·애플리케이션 55·웹 135·외부 26·bootstrap 34·MySQL 39·Redis 4개에 실패·
  제외가 없고 REST Docs 조각은 84개다. MySQL 테스트는 8.4.13 이미지를 썼다. `docker build`, CI의 배포 이미지 다이제스트 계약
  검증과 운영 이미지 기동 단계의 로컬 실행, promtool 규칙 검사·테스트, `kubectl kustomize` 렌더링, 문서 링크 검사가
  통과했다. kubeconform은 로컬에 없어 원격 CI에서 확인한다. 로그는 세션 스크래치패드(`f602be4c-…/scratchpad`)의
  `explain/`·`final-verify-r8.log`·`smoke-r8.log`·`mysql-datadir-check.log`·`mutate-run.log`다. 이전 검증 항목 4개는
  [2026-10-30 검증 기록](docs/HISTORY/2026-10-30-verification-history.md)으로 옮겼다. 실제 클러스터 적용은 확인하지 않았다.
  위 커밋과 이 기록 커밋은 사용자 승인으로 push했고, setup-java 6.1.0 제안 `ljkhyeong/baton-go#7`을 병합하고
  `ljkhyeong/baton-go#8`은 닫았다. 그 CI 결과는 이 기록에 넣지 않았다.

- 남은 항목 재점검은 미커밋 변경이 없는 `main`의 `c5acd71`에서 시작해 `06c8419`·`42b0744`에 저장했다.
  - MySQL 8.4.11 태그가 2026-10-12에 다시 빌드되어 고정 digest(2026-07-27 빌드)와 달라졌다. CI와 같은 Trivy
    0.72.0으로 linux/amd64 두 빌드를 비교해 OS 패키지 취약점 82건(HIGH 43·MEDIUM 39, openssl·curl·libevent·
    sqlite·libxml2 등)이 해소되고 새로 생긴 항목이 없음을 확인했다. Compose와 비공개 서버 오버레이의 digest를
    함께 바꿨다. 남은 CRITICAL 1건은 공식 이미지의 `gosu`에 든 Go 표준 라이브러리라 상위 이미지 갱신을 기다린다.
  - 버전이 같아 데이터 형식 변경은 없다. 이전 빌드로 초기화하고 시험 행을 넣은 볼륨을 새 빌드가 열어 같은 행과
    `CHECK TABLE` OK를 반환했고 오류 로그는 없었다. 운영 DB가 아직 없어 백업·복원 검증 대상은 없다. 배포 뒤의
    digest 변경은 [배포 절차](docs/RUNBOOK/kubernetes-private-server-deployment.md)대로 DB 업그레이드로 다룬다.
  - Dependency Review의 제외 근거였던 비공개 저장소 비용이 저장소 공개로 맞지 않게 됐다. 의존성 그래프는
    Maven 102개(`main` CI 제출)와 액션 7개로 PR에서 비교할 대상이 액션뿐이라, 제외를 유지하고 근거와 재검토
    조건을 고쳤다. 같은 조회에서 공개 저장소에 무료인 secret scanning·push protection이 꺼져 있어 관리자용
    설정·확인·경고 대응 절차를 추가했다. 저장소 보안 설정은 바꾸지 않았다.
  - 열린 PR·이슈와 열린 Dependabot 경고는 없다. 소스의 TODO·FIXME와 Gradle 구성 경고가 없고, 일회용 init
    스크립트로 `-Xlint:all`을 켠 컴파일에서도 사용 중단 API 경고 없이 try-with-resources 범위용 변수 경고 2건만
    나왔다. 검사 도구 이미지(Trivy 0.76.0·Prometheus 3.15.0·Alertmanager 0.34.1·kubeconform 0.8.0)와 Gradle
    9.8.1은 새 버전이 있지만 제품 이미지에 들어가지 않고 CI가 정상이라 올리지 않았다.

  Java 21에서 `./gradlew --no-daemon :bootstrap:mysqlTest`를 통과했다. MySQL 30개에 실패·제외가 없고 테스트
  기록에서 새 digest 이미지 사용을 확인했다. CI의 배포 이미지 다이제스트 계약 검증 단계를 로컬에서 실행해
  통과했고 문서 링크 검사는 문제 0건이다. Java·Redis 변경이 없어 일반 빌드와 Redis 통합 검증은 반복하지 않았다.
  로그는 세션 스크래치패드(`f602be4c-…/scratchpad`)의 `trivy-mysql/`·`mysql-datadir-check.log`·
  `mysql-test-r7.log`·`xlint-compile.log`다. `06c8419`·`42b0744`와 이 기록 커밋은 사용자 승인으로 함께
  push했으며 그 CI 결과는 이 기록에 넣지 않았다.

- 원격 반영 확인과 의존성 제안 처리는 미커밋 변경이 없는 `main`의 `97241e5`에서 시작해 Dependabot 병합
  `effe7f7`·`4e2da51`과 `fc77b43`에 저장했다.
  - 사용자 승인으로 `f7459e1..97241e5`를 원격 `main`에 반영했다. push CI
    [38874692945](https://github.com/ljkhyeong/baton-go/actions/runs/38874692945)에서 필수 검증·Java 의존성 알림
    연동·게시 이미지 출처 증명이 모두 성공했다. 로컬에 없어 남겼던 kubeconform strict schema 검증도 통과했다.
  - 액션 6건(`ljkhyeong/baton-go#6`)은 checkout v6.1.0→v7.0.1, setup-java v5.7.0→v6.0.1, setup-gradle
    v6.3.0→v6.4.0, setup-kubectl v5.1.0→v5.2.0, upload-artifact v7.0.1→v7.0.2, download-artifact v8.0.1→v8.0.2다.
    고정 SHA 6개가 태그와 일치한다. checkout v7의 포크 PR 체크아웃 차단은 `pull_request_target`·`workflow_run`에만
    적용되고, 사용하는 입력과 기본값, node24 실행 환경은 그대로다. setup-java v6에서 새로 켜지는 기본값은 Java
    문제 표시기뿐이며 JDK 캐시는 `cache`를 지정할 때만 동작한다.
  - Temurin(`ljkhyeong/baton-go#4`)은 빌드·실행 단계가 함께 `21.0.12+8`→`21.0.12.1+1`로 바뀌고 digest가 현재
    `21-jdk-alpine`·`21-jre-alpine` 태그와 일치한다. PR CI는 이전 기준 `f7459e1`에서 돌았으므로 `/startupz` 상태
    확인을 넣은 현재 `main`에 적용해 `docker build`와 CI 운영 이미지 기동 단계를 로컬에서 다시 통과시켰다.
  - 사용자 승인으로 두 PR을 merge commit으로 병합했다. 병합 후 `main` CI
    [38876120475](https://github.com/ljkhyeong/baton-go/actions/runs/38876120475)에서 세 작업이 모두 성공했고,
    새 Java 문제 표시기·Gradle 버전 상태 주석은 나오지 않았다. 먼저 병합한 `effe7f7`의 실행은 같은 동시 실행
    그룹이라 취소됐다.
  - `setup-gradle`·`setup-kubectl`의 메이저 태그 주석(`# v6`·`# v5`)은 상위 태그가 옮겨지면 고정 SHA와 대조할 수
    없어 같은 커밋의 `v6.4.0`·`v5.2.0`으로 바꿨다(`fc77b43`).
  - Dependabot 경고는 10건 모두 `fixed`이고 최신 안정 Boot는 4.1.1이다. Tomcat 11.0.27이 나왔지만 고정한
    11.0.26·Jackson 3.1.7에 OSV 기준 알려진 취약점이 없어 해석 규칙을 그대로 둔다.

  Java·SQL 변경이 없어 로컬 Gradle·통합 검증은 반복하지 않고 원격 CI 결과를 쓴다. 로그는 세션 스크래치패드
  (`f602be4c-…/scratchpad`)의 `docker-build-pr4.log`·`smoke-pr4.log`다. `fc77b43`과 이 기록 커밋은 함께 push했으며
  그 CI 결과는 이 기록에 넣지 않았다. 두 릴리스 워크플로의 액션 변경은 실행 기회가 없어 다음 작업 5로 남겼다.

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
   BATON의 GO 계약 검증을 다시 실행한다. BATON 아웃박스 결과 분류에는 PRD-0003에 추가한
   `EXPIRES_AT_ALREADY_PASSED`·`PURGED`·자동 재시도하지 않는 복구 오류와 폐기 응답 기준을 반영한다. BATON Actions의
   `BATON_GO_CONTRACT_READ_TOKEN` 등록을 확인한 뒤 GitHub 품질 게이트를 실행한다.
   실제 서비스 JWT와 HTTPS 환경에서 생성 응답 유실 후 취소·폐기까지 재시도되는지 확인한다.
   상태 확인 장애 경보와 링크 생성·폐기·상태 일괄 조회도 검증한다.
   상태 확인·링크 생성 요청 저장·GO API 호출은 기본 중지하며 기존 방의 링크 일괄 생성은 포함하지 않는다.
   카카오 앱의 무료 사용 설정·실제 메시지 전송과 실기기 QR 스캔을 확인한다.
3. 외부 프록시에서 공개 `/l`과 비공개 `/api/v1` 라우팅을 분리하고, 분산 요청률 제한과
   `/l/{code}` 접근 로그 마스킹을 적용한다. 분산 제한 구현은 준비되었으며 실제 Redis·Ingress 검증은 남아 있다.
   Redis 노드가 연결 종료 없이 사라졌을 때 공개 링크가 `503`에서 복구되는 시간도 이때 확인한다. Lettuce에 TCP
   keepalive를 따로 설정하지 않아 운영체제가 연결을 끊을 때까지 이어질 수 있다.
4. 실제 비공개 클러스터에서 DNS·TLS·CNI·NetworkPolicy·startup/liveness/readiness probe와
   PVC·Secret의 생성·교체·복구·삭제를 검증한다. 현재 ingress 전용 NetworkPolicy에 더해 환경별 DNS·MySQL
   egress 허용 목록과 기본 차단 정책을 정하고 실제 CNI의 허용·차단 결과를 기록한다.
   `restricted` 정책 강제 적용 전에는 고정 MySQL 이미지로 신규·복원·현재 PVC의 기동,
   TLS·초기화·마이그레이션을 검증한다. MySQL을 멈춘 동안 GO Pod가 서비스에 남아 공개 링크에 `500` 안내 화면을
   반환하고 `BatonGoDependencyUnavailable`이 발생하는지, 새 Pod가 `/startupz`를 통과하지 못하는지도 확인한다.
5. 첫 릴리스 태그에서 보관 워크플로의 증명 재검증을 실행한다. 배포 전 확인 명령으로 승인한
   다이제스트만 배포되는지 검증하고 결과를 배포 기록에 남긴다. 두 릴리스 워크플로는 PR·push CI에서 실행되지
   않으므로 `ljkhyeong/baton-go#6`에서 올린 checkout v7.0.1·upload-artifact v7.0.2도 이때 함께 확인한다.
6. Prometheus 수집, 경보 규칙, 알림 경로와 담당자를 연결하고 마이그레이션 실패,
   Pod 비정상, 5xx·429, 시작 상태(DB·Redis 연결), 저장 대상 계약 위반, PVC 용량과 백업 실패 경보의
   시험 결과를 기록한다.
7. 재시도 시 기존 URL 반환 보장 기간과 만료·폐기 링크 보존 기간, 백업·감사
   보존과 PVC 경보·증설 기준을 함께 결정한다. 정리 구현은 준비되어 있으며 이 결정 전에는
   자동 정리를 활성화하지 않는다. 운영 트래픽의 종료 링크 재접속 시간 분포를
   [보존 기간 결정 자료](docs/RUNBOOK/link-retention.md#보존-기간-결정-자료)의 조회로 확인해 근거로 쓴다.
8. 플랫폼 백업 정책에 RPO·RTO·주기·보존 기간·담당자·실패 경보·결과 보관 위치를 명시하고,
   DB와 같은 버전의 `baton-go-link-code-key-ring` 키 묶음을 한 복구 단위로 사용한 격리 복원,
   장애 대응과 이미지 되돌리기 훈련을 완료한다.
9. HMAC 비밀값 유출 대응은 [키 유출 대응 절차](docs/RUNBOOK/link-code-key-compromise.md)로 정했다. 운영 데이터가
   없는 격리 환경에서 절차를 훈련하고 차단부터 재개까지 시간, 동일 요청 재시도가 폐기된 링크를 돌려주는지와
   시작 검사가 유출 키 제거를 막는지를 배포 검증 기록에 남긴다. 평상시 키 교체도
   [운영 절차](docs/RUNBOOK/link-code-key-rotation.md)에 따라 배포·전환·복원을 검증한다.
10. 저장소 관리자가 [비밀값 유출 감지](docs/RUNBOOK/external-api-integrations.md#비밀값-유출-감지) 절차로
    secret scanning·push protection을 켜고, 기존 이력 검사 결과와 경고 대응을 확인한다.

운영·복구 절차는
[비공개 Kubernetes 배포 절차](docs/RUNBOOK/kubernetes-private-server-deployment.md)와
[발급 키 버전 결정](docs/ADR/0011_link-code-key-ring/adr.md)을 따른다.
