# 인수인계

## 현재 상태

- GO의 링크 생성·조회·접속 처리·폐기와 동일 요청 재시도는 구현되어 있다.
  동작 기준은 [제품 명세](docs/PRD/0001_product-baseline/spec.md),
  [API 계약](docs/PRD/0002_api-contract/spec.md),
  [BATON·ROUND 연동 계약](docs/PRD/0003_cross-service-link-contract/spec.md)이다.
  GO 링크는 위치만 제공하며 접근 권한은 BATON·ROUND가 판단한다.
- 종료 링크 자동 삭제, Redis 분산 요청 제한, 대상 계약 점검·폐기 API는 기본 중지 상태다.
  실제 운영 환경 검증과 배포는 남아 있다.
- `go.b4ton.com` 운영 예시와 Cloudflare DNS API 인증서 갱신·Discord·Slack 웹훅 알림의 선택 설정을
  추가했다. [외부 API 연동 절차](docs/RUNBOOK/external-api-integrations.md)를 따르며
  서버 설치, 실제 토큰 연결, DNS 변경, 인증서 발급과 메시지 전송은 실행하지 않았다.
- Dependabot의 CI 액션·Java 21 기반 이미지 업데이트 제안 설정과 GitHub 공식 Slack 앱의 CI 구독 절차를 추가했다.
  Java 이미지는 사용하지 않는 빌드 인자를 제거하고 Dockerfile의 `FROM`에 직접 고정했다.
  Java 메이저 업데이트와 MySQL 이미지는 자동 제안에서 제외한다.
  원격 기본 브랜치 반영과 실제 채널 연결은 아직 하지 않았다.
- GitHub 저장소의 Dependabot alerts를 활성화하고 조회로 확인했다. 자동 수정 PR은 중지 상태다.
  기존 Trivy 결과의 Java 패키지 목록을 `main` CI 성공 후 의존성 API로 제출하는 설정을 추가했다.
  실제 Java 목록 제출은 원격 `main` 반영 후 확인해야 한다.
- `main`의 빌드·이미지·실행·DB 검증 후 같은 이미지를 GHCR에 게시하는 CI 설정을 추가했다.
  게시 다이제스트·검사 메타데이터를 별도 산출물에 기록하며 재빌드하지 않는다.
  [게시·배포 참조 절차](docs/RUNBOOK/image-security-reports.md#ghcr-자동-게시와-배포-참조)를 따르며,
  원격 반영·실제 GHCR 인증과 게시·홈서버 이미지 수신은 아직 확인하지 않았다.
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
- 멱등 예약의 공개 출처 복구는 저장값 누락과 잘못된 URL 형식만
  `PUBLIC_LINK_ORIGIN_REPLAY_UNAVAILABLE`로 처리한다. 그 밖의 실행 오류는 복구 오류로 바꾸지 않는다.
- 종료 링크 정리와 Redis 분산 요청 제한의 수치·시간 범위는 Spring `@ConfigurationProperties`
  검증을 사용한다. 기능 활성화에 따른 필수값 조건만 생성자에서 확인한다.
- 종료 링크 자동 정리 실행 간격은 최소 1초다. 빈 값과 `0s`는 설정 바인딩 단계에서 거부한다.
- 자동 정리를 켜면 Pod별 실행 완료 시각과 설정 간격을 지표로 제공한다. 완료 신호가 실행 간격의
  3배(최소 3분)를 넘긴 상태로 2분 지속되면 정체 경보가 발생한다.
- 준비 상태는 DB와 활성화한 분산 요청 제한 Redis를 확인한다. Redis `PING`이 실패하면
  `/readyz`는 503이며 `/livez`는 정상 상태를 유지한다.
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
  [구현·검증 기록](docs/HISTORY/2026-09-08-implementation-verification.md)에 보관한다.

## 최근 검증

- 이미지 게시·도메인 알림은 미커밋 변경이 없는 `main`의 `d845830`에서 시작해 설정을 `6344b8a`에 저장했다.
  YAML·JSON 파싱, Bash 구문·ShellCheck와 기존 `rhysd/actionlint:1.7.12`의 워크플로 검사를 통과했다.
  기존 CI 단계 보존, 전체 검증 뒤 `main` push에서만 게시하는 조건, 권한과 문서 링크를 확인했다.
  검증 환경은 `/private/tmp/baton-go-integration-validation-20260912`다.
  `ghcr-publish.sh`는 CI에서 추출한 게시 단계이며 `ghcr-flow-validation.py`·`ghcr-flow-validation.log`에
  응답 대체 검증이 있다. 게시 성공 시 다이제스트·메타데이터·요약 기록, 이미지·커밋 불일치 시 게시 차단,
  전송 실패 시 완료 기록 생략과 임시 인증 파일 정리를 확인했다.
  실제 로컬 레지스트리 전송은 Docker Desktop의 데몬→호스트 포트 연결 거부로 실패했다.
  임시 포트의 바인딩을 바꿔도 같아 이 경로의 검증을 중단했고 컨테이너·인증 파일은 정리했다.
  `ghcr-native-validation.py`·`ghcr-native-validation.log`에 실패 조건이 있으므로 같은 환경에서 반복하지 않는다.
  이후 문서만 변경했다. Java·이미지·DB 설정이 같아 빌드·Trivy·단위·MySQL·Redis 검증은 반복하지 않았다.
  실제 GHCR 인증·게시, 원격 CI 전체 실행, HetrixTools 계정·Slack 연결과 운영 배포는 하지 않았다.
- 외부 감시 연동은 미커밋 변경이 없는 `main`의 `a1c56e6`에서 시작해 설정·CI를 `1a71dbc`에 저장했다.
  JSON·YAML·Bash·ShellCheck, Prometheus `3.13.2`의 규칙 검사와 Alertmanager `0.34.0`의
  설정·라우팅 검사(주기 신호 전달, 일반 경보·다른 job·다른 경보 이름 제외)를 통과했다.
  변경한 CI의 이미지 기동 단계를 기존 `baton-go:dependabot-validation-20260912` 이미지로 실행해
  감시용 경로의 HTML·JSON 404·키워드와 기존 HEAD·요청 제한 검증을 통과했다. 임시 DB·컨테이너는 정리했다.
  검증 환경은 `/private/tmp/baton-go-integration-validation-20260912`이며
  `heartbeat-ci-validation.sh`·`heartbeat-routes.log`, `uptime-ci-validation.sh`·`uptime-runtime.log`에
  명령과 결과가 있다. 네트워크를 차단한 Alertmanager에서 실제 POST 본문과 반복 전송도 확인했다.
  초기 임시 수신기의 선응답을 본문 수신 후 응답으로 고쳤으며, 재전송 판정 간격 1분에서는
  신호가 120초 간격으로 도착해 판정 간격을 15초로 줄였다. 최종 설정은 60초 간격으로 두 번 수신했고
  본문이 고정 JSON뿐인 것을 확인했다. 재사용할 명령은 `heartbeat-final-validation.sh`와
  `heartbeat-final-http-handler.sh`, 결과는 `heartbeat-final-delivery.log`·`heartbeat-final-*.json`이다.
  Java·의존성 변경이 없어 이미지 빌드·단위·MySQL·Redis 통합 테스트는 반복하지 않았다.
  실제 계정 등록·공개 HTTPS 접속·Slack 전송·클러스터 적용·원격 CI 전체 실행은 하지 않았다.
- 의존성 알림 연동은 미커밋 변경이 없는 `main`의 `2117882`에서 시작해 CI를 `9d11651`에 저장했다.
  YAML·Bash 구문·ShellCheck, 기존 CI 단계 보존과 제출 작업의 실행 조건·권한 분리를 확인했다.
  기존 로컬 이미지 `baton-go:dependabot-validation-20260912`를 Trivy `0.72.0`으로 검사했다.
  라이선스 검사에서는 Java 목록이 없어 기존 CI의 `vuln` 옵션으로 변경했고,
  256MiB 임시 공간 부족은 CI와 같은 디스크 마운트로 수정해 통과했다.
  CI의 변환 단계를 `--network none`으로 실행해 Java 패키지 114개, 커밋·브랜치·실행 정보와
  제출 형식을 확인했다. 빈 결과와 운영체제 패키지만 있는 결과는 거부했다.
  검증 환경은 `/private/tmp/baton-go-integration-validation-20260912`이며
  `dependency-convert-validation.sh`, `dependency-convert.log`, `dependency-scan-retry.log`에
  명령·결과가 있다. GitHub 알림 활성화 전 조회는 중지 상태였고, 활성화 후 조회는 성공했다.
  자동 수정 PR이 꺼져 있고 의존성 그래프 조회가 가능한 것도 확인했다.
  이후 RUNBOOK·HANDOFF만 변경했다. 애플리케이션은 같아 Java·DB·Redis 테스트와 이미지 빌드는
  반복하지 않았다. 실제 스냅샷 제출과 원격 CI 전체 실행·운영 배포는 하지 않았다.
- Java 이미지 업데이트 연동은 미커밋 변경이 없는 `main`의 `a7f9553`에서 시작해
  Dockerfile·Dependabot 설정을 `4adedc7`에 저장했다. 기존 Python·PyYAML 환경으로 설정·시간대를
  검사하고, 이전 Dockerfile의 인자를 확장한 결과와 변경 후 이미지 참조·빌드 명령이 동일함을 확인했다.
  `docker build --tag baton-go:dependabot-validation-20260912 .`가 성공해 컨테이너 안의 Java 컴파일과
  실행 JAR 생성을 확인했다. 로그는
  `/private/tmp/baton-go-integration-validation-20260912/dependabot-image-build.log`다.
  `docker image inspect`로 사용자 `10001:10001`과 실행 명령을 확인했고,
  `docker run --rm --network none --read-only --tmpfs /tmp:rw,nosuid,nodev,size=32m --entrypoint java
  baton-go:dependabot-validation-20260912 -version`으로 Java `21.0.12`를 확인했다.
  이후 RUNBOOK·HANDOFF 문서만 변경했다. 애플리케이션·사용 이미지 버전·검사 설정은 같아
  단위·MySQL·Redis 테스트와 Trivy 검사는 반복하지 않았다. Dependabot의 실제 PR 생성·필터 적용과
  원격 CI 실행·이미지 게시·운영 배포는 실행하지 않았다.
- 인증서 감시 연동은 미커밋 변경이 없는 `main`의 `9fbcf68`에서 시작해 설정·테스트·CI를
  `6f02e56`에 저장했다. 변경한 CI의 YAML·Bash 구문·ShellCheck와 Prometheus `v3.13.2`의
  `promtool check config --syntax-only` 2개 설정, `check rules` 18개 규칙, 기존 경보 테스트를
  통과했다. 인증서 테스트 5개 시나리오는 YAML 중복 키와 빈 샘플 수를 수정한 뒤 통과했다.
  Alertmanager `v0.34.0`의 `amtool check-config`와 `config routes test`로 두 채널 각각의
  GO·인증서 전달과 다른 서비스 제외를 확인했다. 모든 컨테이너는 `--network none`으로 실행했다.
  명령·결과는 `/private/tmp/baton-go-integration-validation-20260912/tls-ci-validation.sh`와
  같은 디렉터리의 `tls-validation.log`에 있다. 이후 README·RUNBOOK·HANDOFF 문서만 변경했다.
  Java·DB·Redis·인증서 발급 설정과 알림 본문은 바뀌지 않아 해당 검증은 반복하지 않았다.
  실제 지표 수집·인증서 갱신·알림 수신과 GitHub CI 전체 실행은 하지 않았다.
- 추가 연동 검토는 미커밋 변경이 없는 `main`의 `4c87ba9`에서 시작했고 Dependabot 설정은
  `d7c81f8`에 저장했다. 기존 Python·PyYAML 환경으로 `.github/dependabot.yml` 구문과
  `Asia/Seoul` 시간대를 확인했다. `.github/workflows/ci.yml`의 실제 액션 5종과 `CI` 이름,
  `origin`의 `ljkhyeong/baton-go` 주소를 확인해 자동 제안 대상과 Slack 구독 명령에 반영했다.
  GitHub 공식 설정 문서와 Docker 파일 수집·파서를 확인했으며, 당시 `ARG` 기반 이미지는 자동
  제안 대상에서 제외했다. 이후 README·RUNBOOK·HANDOFF 문서만 변경했다.
  애플리케이션·기존 CI·웹훅 설정이 바뀌지 않아 해당 테스트는 반복하지 않았다.
  Dependabot의 실제 PR 생성·GitHub CI 실행·Slack 앱 연결과 채널 구독은 확인하지 않았다.
- Slack 추가는 미커밋 변경이 없는 `main`의 `6e9d39f`에서 시작해 `ec44c1d`에 설정·CI를 저장했다.
  기존 검증 환경으로 변경한 CI 단계의 YAML·Bash·ShellCheck, Alertmanager `v0.34.0`의
  `amtool check-config`와 `config routes test`를 통과했다. Discord·Slack 각각의 GO 경보 전달과
  다른 서비스 제외를 확인했다. `template render`로 Slack 발생·해제 메시지 5개 필드의
  표시 내용과 비밀 필드 제외를 확인했다. 입력은 상태별 파일로 분리했고 재시도 없이 성공했다.
  로그는 `/private/tmp/baton-go-integration-validation-20260912/slack-validation.log`다.
  이후 README·RUNBOOK·HANDOFF 문서만 변경했다. Java·DB·Redis·인증서 설정은 바뀌지 않아
  해당 검증은 반복하지 않았다. 실제 웹훅 등록·메시지 전송과 GitHub CI 전체 실행은 하지 않았다.
- 2026-09-12 기준 리비전 `400b739`의 깨끗한 `main`에서 시작했고 연동 설정은 `051c2aa`에 저장했다.
  이어진 문서 변경은 README·RUNBOOK·HANDOFF이며 애플리케이션 코드·의존성 변경은 없다.
  `kubectl kustomize`로 `deploy/k8s/overlays/private-server`와
  `deploy/k8s/integrations/cloudflare-tls`를 렌더링했고, cert-manager `v1.21.2` 공식 CRD의
  `openAPIV3Schema`로 Issuer·Certificate 2개를 검사했다.
  `.github/workflows/ci.yml`의 Discord 단계는 YAML·Bash 구문·ShellCheck와 로컬 실행을 통과했다.
  Alertmanager `v0.34.0`의 `amtool check-config`, `config routes test`로 GO·다른 서비스 분기를
  확인했고 `template render`로 발생·해제 문구와 비밀 필드 제외를 확인했다. 해제 문구의 첫 도구 실행은
  실패했으나 별도 입력 파일로 다시 검사해 성공했다. 컨테이너는 모두 `--network none`으로 실행했다.
  검증 환경은 `/private/tmp/baton-go-integration-validation-20260912/bin/python`(PyYAML·jsonschema),
  같은 디렉터리의 `cloudflare-tls.rendered.yaml`, `private-server.rendered.yaml`,
  `cert-manager.crds.yaml`, `alertmanager-validation.log`에 설정과 결과가 있다.
  Java·DB·Redis와 기존 경보 규칙은 바뀌지 않아 해당 테스트는 실행하지 않았다.
  GitHub CI 전체 실행과 클러스터 적용·인증서 발급·갱신·웹훅 수신 검증은 남아 있다.
- 2026-09-12 `d3addd6`에서 ArchUnit으로 도메인·애플리케이션·컨트롤러·어댑터의 의존 방향을
  자동 검사하도록 했다. 구조 규칙 5건과 `./gradlew --no-daemon test`가 성공했다.
  DB·Redis 동작은 바뀌지 않아 태그 통합 테스트는 반복하지 않았다.
- 2026-09-12 `e3c88c4`에서 자동 정리 스케줄러의 실행 완료 시각과 설정 간격 지표를 추가했다.
  성공·실패 후 완료 시각 갱신과 기존 정리·실패 카운터를 대상 테스트 1건으로 확인했고,
  `./gradlew --no-daemon test`도 성공했다. DB·Redis 동작은 바뀌지 않아 태그 통합 테스트는 반복하지 않았다.
- 2026-09-12 `1b610d2`에서 자동 정리 실행 완료 신호 정체 경보를 추가했다. 정상 실행,
  장기 실행 간격, 정체·복구 시나리오를 포함한 전체 `promtool` 규칙 테스트가 성공했다.
- 2026-09-12 `6414c83`에서 종료 링크 자동 정리 실행 간격에 Spring 설정 검증을 추가했다.
  빈 값·`0s` 거부를 포함한 설정 테스트 7건과 `./gradlew --no-daemon test`가 성공했다.
  DB·Redis 동작은 바뀌지 않아 태그 통합 테스트는 반복하지 않았다.
- 2026-09-12 `75690fd`에서 Deployment 롤링 업데이트의 가용 Pod 유지와 10초 안정화 조건을
  명시했다. Kubernetes 1.36.1 엄격 스키마로 bootstrap 1개, private-server 10개, monitoring
  2개 리소스를 검증해 모두 통과했다. 애플리케이션 코드는 바뀌지 않아 Gradle 테스트는 반복하지 않았다.
- 2026-09-12 `fc578fb`에서 Spring Boot 정상 종료를 적용했다. `./gradlew --no-daemon build`가
  성공해 설정 로딩, 일반 테스트와 실행 JAR 생성을 확인했다. DB·Redis 동작은 바뀌지 않아 태그
  통합 테스트는 반복하지 않았다.
- 2026-09-12 `99d24a7`에서 Pod별 준비 상태 지속 실패 경보를 추가했다. 규칙 문법 검사와
  정상·간헐 실패·지속 실패·복구 시나리오를 포함한 전체 `promtool` 규칙 테스트가 성공했다.
  애플리케이션 코드는 바뀌지 않아 Gradle 테스트는 반복하지 않았다.
- 2026-09-12 `4beed9d`에서 분산 요청 제한 Redis 상태 확인을 준비 상태에 추가했다.
  상태 확인 대상 테스트 3건, 실제 Redis 통합 테스트 3건과 `./gradlew --no-daemon test`가
  성공했다. MySQL 동작은 바뀌지 않아 MySQL 통합 테스트는 반복하지 않았다.
- 2026-09-12 `e4c2c0a`에서 공개 출처 복구의 광범위한 `RuntimeException` 처리를 제거했다.
  애플리케이션 대상 테스트, 공개 출처가 없는 기존 MySQL 예약 통합 테스트와
  `./gradlew --no-daemon test`가 성공했다. Redis 동작은 바뀌지 않아 Redis 통합 테스트는 반복하지 않았다.
- 2026-09-12 `19a2823`에서 종료 링크 정리와 Redis 분산 요청 제한의 직접 범위 비교를
  `@Min`·`@Max`·`@DurationMin`·`@DurationMax`로 교체했다. 설정 경계 13건, 실제 Redis 제한
  동작 2건과 `./gradlew --no-daemon test`가 성공했다. DB 동작은 바뀌지 않아 MySQL 통합
  테스트는 반복하지 않았다.
- 2026-09-12 `41cd625`에서 링크 생성 예약의 `INSERT IGNORE`를 제거했다. 변경 후 같은 요청의
  동시 생성·최초 트랜잭션 롤백·서버별 공개 출처 차이 3건과 비중복 MySQL 저장 오류 전파 1건이
  통과했다. `./gradlew --no-daemon test`도 성공했으며 Redis 동작은 바뀌지 않아 Redis 통합
  테스트는 반복하지 않았다.
- 2026-09-12 `bf93553`에서 Spring `JwtClaimValidator`로 관리 JWT의 `sub`를 필수화했다.
  실제 서명 JWT의 `sub` 누락·빈 문자열·공백 거부와 기존 유효 토큰을 대상 테스트 7건으로
  확인했다. `./gradlew --no-daemon test`도 성공했다. DB·Redis 동작은 바뀌지 않아 태그 통합
  테스트는 반복하지 않았다.
- 2026-09-12 `349ec9f`에서 일반 링크의 최초 폐기와 반복 폐기 완료 이력을 구분했다.
  `./gradlew --no-daemon test`가 성공해 애플리케이션·웹·부트스트랩 등 일반 테스트를 모두 통과했다.
  이에 앞서 애플리케이션과 웹 대상 테스트를 동시에 실행했을 때 같은 Gradle 출력 디렉터리 경합으로
  애플리케이션 컴파일 한 번이 실패했다. 웹 계약 테스트 41개는 통과했고, 경합이 끝난 뒤
  애플리케이션 서비스 테스트 20개를 다시 실행해 모두 통과했다. MySQL·Redis 동작은 바뀌지 않아
  태그 통합 테스트는 반복하지 않았다.
- 2026-09-08 검증 기준은 GO `85f6189`다. `8c3d062`의 미커밋 변경이 없는 상태에서 시작했다.
  검증한 코드·테스트 11개 파일을 커밋했고 이후에는 계약 문서와 인계 기록만 수정했다.
- `GET /api/v1/links/batch?linkIds=...`를 추가했다. 최대 100개 ID를 한 번의 DB 조회로 읽고,
  요청 순서·중복 제거·동일 판정 시각을 보장한다. 없거나 허용되지 않은 링크는 `notFoundIds`로 반환한다.
  기존 읽기 scope와 대상 정책을 사용하고, 목록 조회와 대상 검사·결과 변환을 공유한다.
- Java 21(`/Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home`)과 Docker로
  애플리케이션 46개·웹 124개·MySQL 43개를 확인했다. 최종 실패·제외는 없다.
  REST Docs 14개 예시와 목록·일괄 조회 예시의 판정 시각 일치, 변경 문서의 로컬 링크 42개도 확인했다.

```bash
./gradlew --no-daemon :application:test :adapter-in-web:apiContractDocs :bootstrap:mysqlTest
./gradlew --no-daemon :adapter-in-web:test --tests '*LinkManagementHttpContractTest' \
  :adapter-in-web:apiContractDocs :bootstrap:mysqlTest
./gradlew --no-daemon :adapter-in-web:test --tests '*LinkManagementHttpContractTest' \
  :adapter-in-web:apiContractDocs
```

- 최초 실행은 애플리케이션 46개·웹 123개 통과 후 새 HTTP 테스트의 예시 시각 불일치 1건으로 중단했다.
  예시를 고친 뒤 해당 클래스 40개와 미실행 MySQL 43개를 통과했다. 기존 목록 예시의 시각도 맞춘 뒤
  HTTP 40개와 문서 생성만 다시 실행했다. 변경되지 않은 테스트의 성공 결과는 재사용했다.
- 위 명령 순서의 로그는 `/private/tmp/baton-go-batch-lookup-tests-20260908.log`,
  `/private/tmp/baton-go-batch-lookup-final-tests-20260908.log`,
  `/private/tmp/baton-go-batch-lookup-docs-tests-20260908.log`다. 합산 결과와 예시 확인은
  `/private/tmp/baton-go-batch-lookup-verification-20260908.json`에 있다.
- Redis 변경이 없어 통합 검증은 반복하지 않았다. 운영 배포와 BATON 호출부의 일괄 조회 전환은 남아 있다.
- BATON `codex/go-link-integration-20260905`의 `90557822`에 공유 문구와 검증 기록을 반영했다.
  코드 기준은 `bc888b15`다. 타입·빌드 검사와 공유 시나리오 12개를 통과하고, 안내 위치를 맞춘 뒤
  직접 복사 3개만 추가 검증했다. 명령·포트 조건·로그는
  `/private/tmp/baton-go-integration-20260905/output/verification/latest.md`에 있다.
- 실제 카카오 전송과 BATON 메인 병합은 남아 있다.

## 다음 작업

1. [대상 계약 v1 점검·폐기 절차](docs/RUNBOOK/target-contract-v1-remediation.md)에 따라
   배포 DB의 모든 링크를 점검하고 `unrevoked non-compliant=0`, 승인되지 않은 `HOLD=0` 결과를 기록한다.
2. 실제 릴리스 이미지에서 BATON·ROUND 인증, 공개 HTTPS와 외부 coturn을 연결한다.
   세션·CSRF·쿠키·JWK 교체·TURN·WebSocket 검증 결과를 기록한다. GO 관리 API는
   Spring Security JWT와 작업별 scope로 전환했으므로 실제 발급자 식별자·JWK, 서비스 신원,
   audience·scope와 키 교체를 비공개 네트워크에서 검증한다.
3. 최신 BATON 변경과 GO 연동 브랜치를 병합하고 충돌한 동작을 검증한다.
   GO 연동과 이후 BATON·공휴일 기능을 함께 유지한다. 아직 배포하지 않은 GO 마이그레이션 V40~V42는
   계정 비활성화 V39 다음 순서이며, 운영 DB에 적용한 파일은 교체하지 않는다.
   고정 GO 커밋 `b4b4df22cbabf7a71697d60db5294ecfeb857e86`의 원격 반영과 BATON Actions의
   `BATON_GO_CONTRACT_READ_TOKEN` 등록을 확인한 뒤 GitHub 품질 게이트를 실행한다.
   실제 서비스 JWT와 HTTPS 환경에서 생성 응답 유실 후 취소·폐기까지 재시도되는지 확인한다.
   상태 확인 장애 경보와 링크 생성·폐기·상태 일괄 조회도 검증한다.
   상태 확인·링크 생성 요청 저장·GO API 호출은 기본 중지하며 기존 방의 링크 일괄 생성은 포함하지 않는다.
   카카오 앱의 무료 사용 설정·실제 메시지 전송과 실기기 QR 스캔을 확인한다.
4. 외부 프록시에서 공개 `/l`과 비공개 `/api/v1` 라우팅을 분리하고, 분산 요청률 제한과
   `/l/{code}` 접근 로그 마스킹을 적용한다. 분산 제한 구현은 준비되었으며 실제 Redis·Ingress 검증은 남아 있다.
5. 실제 비공개 클러스터에서 DNS·TLS·CNI·NetworkPolicy·startup/liveness/readiness probe와
   PVC·Secret의 생성·교체·복구·삭제를 검증한다. 현재 ingress 전용 NetworkPolicy에 더해 환경별 DNS·MySQL
   egress 허용 목록과 기본 차단 정책을 정하고 실제 CNI의 허용·차단 결과를 기록한다.
   `restricted` 정책 강제 적용 전에는 고정 MySQL 이미지로 신규·복원·현재 PVC의 기동,
   TLS·초기화·마이그레이션을 검증한다.
6. 릴리스 이미지 다이제스트, SBOM, 취약점 검사, 서명·빌드 출처 검증 결과를 보존하고
   승인된 이미지만 배포되는지 확인한다.
7. Prometheus 수집, 경보 규칙, 알림 경로와 담당자를 연결하고 마이그레이션 실패,
   Pod 비정상, 5xx·429, DB 준비 상태, 저장 대상 계약 위반, PVC 용량과 백업 실패 경보의
   시험 결과를 기록한다.
8. 재시도 시 기존 URL 반환 보장 기간과 만료·폐기 링크 보존 기간, 자동 정리 뒤 HTTP 의미, 백업·감사
   보존과 PVC 경보·증설 기준을 함께 결정한다. 정리 구현은 준비되어 있으며 이 결정 전에는
   자동 정리를 활성화하지 않는다.
9. 플랫폼 백업 정책에 RPO·RTO·주기·보존 기간·담당자·실패 경보·결과 보관 위치를 명시하고,
   DB와 같은 버전의 `BATON_GO_LINK_CODE_SECRET`을 한 복구 단위로 사용한 격리 복원,
   장애 대응과 이미지 되돌리기 훈련을 완료한다.
10. HMAC 비밀값 유출 시 Secret만 바꾸지 말고 링크 생성과 공개 경로를 먼저 차단한다.
    기존 링크 폐기·재발급과 키 목록 전환을 함께 수행하는 복구 절차를 정하고 훈련한다.
    평상시 키 교체도 [운영 절차](docs/RUNBOOK/link-code-key-rotation.md)에 따라 배포·전환·복원을 검증한다.

운영·복구 절차는
[비공개 Kubernetes 배포 절차](docs/RUNBOOK/kubernetes-private-server-deployment.md)와
[HMAC 키 정보 등록 결정](docs/ADR/0004_link-code-key-binding/adr.md)을 따른다.
