# BATON GO

BATON GO는 단순 URL 축약기가 아니라 BATON과 ROUND를 위한 정책형 링크 게이트웨이다.
공개 링크 코드의 수명주기와 신뢰된 대상 라우팅을 담당하되, 각 제품의 최종 접근 권한은
소유 서비스가 계속 판단한다.

## 첫 구현 범위

- canonical UUID 멱등성 키와 HMAC 기반 128-bit 공개 코드 발급
- 원문 코드 대신 SHA-256 해시 저장
- `BATON`, `ROUND`의 v1 exact target 조합과 canonical locator만 생성·해석
- 시작 시각, 만료 시각과 즉시 폐기
- 공개 `GET·HEAD /l/{code}` 리다이렉트
- 관리용 `/api/v1/links` 생성·조회·폐기 API
- MySQL/Flyway 영속화, health와 Prometheus endpoint

다음은 아직 구현 범위가 아니다.

- BATON access key를 대체하는 계정·초대 권한
- ROUND WebSocket admission용 BATON participation-grant 발급 endpoint
- 임의 외부 URL 축약
- custom alias, 클릭 분석, Redis rate limit
- 링크 소비 횟수와 일회성 redemption

## 서비스 원칙

```text
BATON GO      코드·시간·폐기·신뢰 대상 라우팅
BATON         workspace·역할·회차·자료 권한
ROUND         room 입장·peer identity·TURN 발급 권한
```

BATON의 `#accessKey`나 ROUND participation grant를 GO의 URL, DB 또는 로그에 넣지 않는다.
현재 GO의 BATON locator는 해당 team의 검증된 access key를 이미 저장한 브라우저가
workspace로 복귀할 때만 사용한다. 신규 브라우저는 기존 BATON 공유 링크를 계속 사용하며,
향후 계정 기반 초대·claim 계약이 생기기 전에는 GO 링크만으로 권한을 부여하지 않는다.

교차 서비스 계약 v1은 정확히 두 locator만 승인한다.

```text
BATON + NAVIGATION    /teams/{teamId}/seasons/{seasonId}
ROUND + MEETING_ENTRY /room/{roomId}
```

ROUND 경로는 pre-join landing일 뿐 입장 권한이 아니다. BATON session과 CSRF 확인 뒤 발급되는
짧은 수명의 participation grant가 실제 admission을 통제한다. 정확한 식별자 문법, endpoint,
cookie와 현재 운영 차단 조건은 [교차 서비스 링크 계약](docs/PRD/0003_cross-service-link-contract/spec.md)을
따른다. GO는 생성과 공개 resolution 양쪽에서 이 exact 조합을 fail-closed로 강제한다. 저장된
비허용 target이나 알 수 없는 enum도 redirect하지 않고 `404 LINK_NOT_FOUND`로 숨긴다. 이때
`baton.go.public.resolver.target.contract.violations` metric을 증가시키고 linkId와 requestId만
포함한 안전한 로그를 남기며 공개 코드, target path와 전체 short URL은 기록하지 않는다.

exact target 정책 구현만으로 공개 production 준비가 끝난 것은 아니다. 계약 강화 전 저장
데이터를 inventory해 비허용 링크를 폐기·재발급해야 하며, PRD-0003의 BATON session·grant,
room mapping, 동일 origin edge, 호출자 outbox 등 나머지 gate도 모두 통과해야 한다.

### 계약 전 데이터 inventory와 정리

legacy target 정리는 자동 bulk 작업이 아니다. 기본 비활성화된 operations API로 raw 행을
안전한 metadata만 포함해 inventory하고, 원본 domain owner가 승인한 link ID를 version과 함께
한 건씩 재검증·폐기한다. API 응답에는 target path, raw enum, code hash, short URL과
idempotency hash가 포함되지 않는다.

maintenance window에는 private ingress에서만 다음 값을 잠시 활성화한다.

```text
BATON_GO_TARGET_CONTRACT_OPERATIONS_ENABLED=true
BATON_GO_TARGET_CONTRACT_OPERATIONS_PRIVATE_INGRESS_CONFIRMED=true
```

두 번째 값은 public edge에서 해당 경로가 차단됐다는 preflight evidence를 확인한 뒤에만
설정한다. 확인 flag 자체가 network boundary를 만들지는 않는다. 둘 중 하나라도 `false`이면
operations controller는 등록되지 않는다.

재발급은 GO가 legacy path를 교정해서 수행하지 않는다. BATON 또는 ROUND의 원본 aggregate
소유자가 authoritative mapping과 새 canonical UUID intent로 정상 생성 API를 호출한다. 전체
절차와 완료 조건은
[target contract v1 정리 runbook](docs/RUNBOOK/target-contract-v1-remediation.md)을 따른다.
operations 구현과 테스트 DB 검증만으로 배포 DB inventory gate를 완료 처리하지 않는다.

BATON mode 운영에서는 `BATON_GO_BATON_BASE_URL`과 `BATON_GO_ROUND_BASE_URL`을 같은 BATON
public HTTPS origin으로 설정한다. edge가 `/room/**`·`/round-ui/**`는 ROUND web으로,
participation-grant refresh는 BATON으로, signaling·TURN만 ROUND 내부 서비스로 라우팅한다.
로컬의 서로 다른 `5173`·`5174` 기본값은 이 end-to-end 운영 계약을 만족하지 않는다.
두 target이 모두 loopback이면 서로 다른 HTTP port를 로컬 개발 예외로 허용한다. 하나라도
비로컬이면 애플리케이션은 두 값을 동일한 HTTPS origin으로 검증하고 시작 단계에서
fail-closed 한다. 이 설정 검증은 실제 edge route·cookie·header E2E를 대신하지 않는다.
loopback은 `localhost`, 선행 0이 없는 canonical dotted-decimal IPv4 `127.0.0.0/8`과 IPv6
loopback literal로 판정하며, 명시적 origin port는 `1..65535`만 허용한다.

## 기술 스택

- Java 21
- Spring Boot 4.1
- Gradle 9.2.1
- MySQL 8, Flyway
- Spring Data JPA
- Actuator, Micrometer Prometheus

내부 구조는 BATON과 happyGallery에서 검증한 포트·어댑터 방향을 따르지만 배포 단위는
하나의 독립 마이크로서비스다.

## 로컬 실행

저장소의 공개 예시 파일을 복사하고 실제 운영 값을 입력한다.

```bash
cp .env.example .env
vim .env
```

`BATON_GO_MANAGEMENT_TOKEN`과 `BATON_GO_LINK_CODE_SECRET`은 각각 32자 이상의 서로 다른
무작위 값이어야 한다. 관리 credential은 HTTP header에 안정적으로 제시할 수 있도록 공백 없는
printable ASCII만 사용한다. 링크 코드 파생 비밀은 기존 DB-key 결합과 복구 호환성을 위해
길이 외의 문법을 추가 제한하거나 trim·Unicode 정규화하지 않고 설정 문자열 그대로 사용한다.
서버는 링크 코드 비밀, 관리 credential과 DB password를 Spring `${...}` placeholder로 다시
해석하지 않고 process environment 원문 그대로 읽는다. 따라서 server와 guard-tool은
`${random.uuid}`, `${HOME}`, backslash나 공백을 포함한 링크 코드 비밀도 동일한 key bytes로
해석한다. 이 환경 변수들은 동일한 command line·JVM property보다 우선한다. credential을
로그나 명령행 인자로 출력해 이 동작을 확인하지 않는다.
이 원문 보장은 process environment에 주입된 뒤의 값에 적용된다. Compose `.env`에서
`${...}` 자체를 credential 일부로 사용할 때는 값을 single quote로 감싸 Compose interpolation을
막고, guard-tool에도 Compose 처리 뒤와 동일한 문자열을 주입한다.
공개된 `replace-with-...` 예시값을 그대로 사용하거나 두 값을 같게 설정하면 애플리케이션은
시작하지 않는다. 링크 코드 파생 비밀은 재시작과 복구 뒤에도 같은 값을 유지해야 기존 생성
요청을 동일 URL로 재생할 수 있다.

`BATON_GO_PUBLIC_BASE_URL`도 모든 실행 환경에서 명시한다. 로컬 개발의 loopback HTTP는
허용하지만, 사용자에게 반환되는 비로컬 short URL origin은 HTTPS여야 한다. 값은 path, query,
fragment나 userinfo가 없는 origin이어야 한다. scheme·host 대소문자, 기본 port와 root slash는
canonical origin으로 정규화해 생성 예약에 저장한다. 같은 intent를 재생할 때는 현재 설정이
아니라 최초 예약의 origin을 사용하므로 origin 이전 뒤에도 같은 short URL을 반환한다. V5 이전
예약처럼 origin 증거가 없으면 현재 값으로 추정하지 않고 운영 오류로 실패한다.

비로컬 공개 base URL을 사용하면서 BATON·ROUND target 환경 변수를 생략해 localhost 기본값이
남은 설정은 시작 단계에서 거부한다. 로컬 기본값은 loopback 공개 origin을 사용하는 개발에만
허용되며 운영 배포는 세 origin을 모두 명시한다.

### HMAC 키와 데이터베이스 결합

애플리케이션은 시작할 때 링크 코드 HMAC 파생 version과 key fingerprint를 DB의 singleton
identity와 대조한다. 신규 DB처럼 링크와 생성 예약이 모두 비어 있으면 현재 secret에 자동
결합한다. 이미 결합된 DB와 identity가 다르거나 기존 데이터가 있는데 identity가 비어 있으면
readiness를 열지 않고 시작에 실패한다. 생성 API도 예약 행을 쓰기 전에 같은 검증을 수행한다.

DB backup과 그 시점의 `BATON_GO_LINK_CODE_SECRET` secret-manager version은 하나의 복구
단위로 보관한다. DB만 또는 secret만 따로 복구하지 않는다. 복구 뒤에는 시작 검증과 보관된
canary 생성 intent의 동일 URL 재생을 확인한다. key ring을 도입하기 전에는 secret을 회전하지
않으며 fingerprint를 로그나 운영 티켓에 기록하지 않는다.

기존 링크가 있는 DB에 이 guard를 처음 도입할 때는 자동 결합하지 않는다. 모든 writer를
중지하고 기존 secret version 및 canary code hash를 오프라인으로 검증한 뒤, 검증된 배포
절차로 singleton identity를 한 번 결합한다. canary나 검증된 secret을 복구할 수 없으면 임의
secret으로 강제 결합하지 않는다. 자세한 결정은
[ADR-0004](docs/ADR/0004_link-code-key-binding/adr.md)를 따른다.

이 저장소는 일반 애플리케이션과 분리된 `guard-tool` 모듈의
`baton-go-guard-binding.jar`를 제공한다. 도구는 canary `Idempotency-Key`를 stdin으로만
받고 저장된 예약·링크의 해시를 검증한 뒤 한 transaction에서 결합한다. 직접 SQL이나 우회
환경 변수 대신
[기존 데이터베이스 HMAC guard 최초 결합 runbook](docs/RUNBOOK/link-code-key-guard-binding.md)을
따른다.

`.env`는 Docker Compose의 dotenv 문법으로 해석하는 데이터 파일이며 셸 스크립트가 아니다.
겉보기에는 `KEY=VALUE` 형식이어도 `source ./.env`로 읽으면 셸이 명령 치환, 변수 확장과
백틱을 실행해 credential 값을 바꾸거나 명령으로 실행할 수 있다. 예시 파일의 JDBC URL
따옴표도 Compose parser 문법이므로 셸 호환성을 뜻하지 않는다.

호스트에서 Gradle로 애플리케이션을 실행할 때는 MySQL만 Compose로 먼저 실행하고,
애플리케이션에 필요한 `BATON_GO_*` 값은 secret manager의 process injection이나 IDE Run
Configuration으로 Gradle 프로세스 환경에 직접 주입한다. Compose용 `.env`를 셸에서
`source`하거나 다른 dotenv parser로 재해석하지 않는다.

```bash
docker compose --env-file .env up -d mysql

# 이 명령을 실행하는 프로세스 환경에는 secret manager나 IDE가 BATON_GO_* 값을 주입한다.
./gradlew :bootstrap:bootRun
```

애플리케이션과 MySQL을 모두 Compose로 실행하려면 다음 명령을 사용한다. 이 경로에서는
Compose가 자기 dotenv 문법으로 `.env`를 읽어 각 컨테이너에 주입한다.

```bash
docker compose --env-file .env up --build -d
docker compose --env-file .env ps
```

## Private Kubernetes 배포

`deploy/k8s`에는 Kubernetes 기본 Kustomize로 조립하는 application과 GO 전용 MySQL
매니페스트가 있다. MySQL은 BATON의 instance·database user·PVC를 공유하지 않으며,
`baton_go` database와 별도 Secret·10Gi PVC를 사용한다. 장기 실행 application은 DML 전용
계정만 받고, Flyway DDL credential은 component scan 없는 일회성 migration Job에만 주입한다.
MySQL은 secure transport를 강제하고 application과 Job은 `VERIFY_IDENTITY` truststore 계약을
사용한다. 특정 StorageClass, Ingress
controller와 TLS 발급 방식은 private cluster마다 다르므로 저장소 base에 고정하지 않는다.

namespace는 workload와 분리해 먼저 적용하고, 실제 origin·immutable image와 외부 Secret을
준비한 뒤 private-server overlay를 적용한다.

```bash
kubectl kustomize deploy/k8s/bootstrap >/dev/null
kubectl kustomize deploy/k8s/overlays/private-server >/dev/null
```

실제 Secret 생성, registry 인증, 배포·backup·restore와 edge 경계는
[Private Kubernetes 배포 runbook](docs/RUNBOOK/kubernetes-private-server-deployment.md)을
따른다. public Ingress는 `/l` Prefix만, private management 경로는 `/api/v1` Prefix만 같은
HTTP Service로 분리해야 하며 Actuator `8081`은 기본 노출하지 않는다. 이 인프라 구성은
PRD-0003의 public production rollout gate를 대신하지 않는다.

application ingress NetworkPolicy는 HTTP를 명시적으로 표시한 ingress namespace에서만 받고,
Actuator는 표시한 monitoring namespace와 client Pod 조합에만 허용한다. 실제 적용 전 CNI의
NetworkPolicy 집행과 kubelet probe 동작을 private cluster에서 검증한다.

기본 애플리케이션 포트는 `8080`, Actuator 관리 포트는 `8081`이다.
Docker는 Actuator 포트의 aggregate `/actuator/health`를 계속 사용한다. 오케스트레이터 probe는
`/actuator/health/liveness`와 `/actuator/health/readiness`를 사용하며, readiness는 DB
연결 상태를 포함하지만 liveness는 포함하지 않는다.

공개 `GET·HEAD /l/{code}`에는 DB 조회 전 인스턴스 aggregate rate-limit backstop이
적용된다. `BATON_GO_PUBLIC_RESOLVER_RATE_LIMIT_CAPACITY`와
`BATON_GO_PUBLIC_RESOLVER_RATE_LIMIT_WINDOW`는 배포 트래픽에 맞춰 명시적으로 설정한다.
이 제한은 client IP나 전달 헤더를 신뢰하지 않는 로컬 안전장치이므로, 여러 replica를
공개할 때는 ingress에서 별도의 분산 rate limit과 `/l/{code}` access-log 마스킹을 적용한다.

## 검증

일반 빌드와 단위 테스트는 로컬 MySQL이나 Docker를 자동으로 요구하지 않는다.

```bash
./gradlew --no-daemon build
```

Flyway/JPA와 동시 생성 동작을 포함한 MySQL 통합 검증은 Docker가 실행 중인 환경에서
별도로 수행한다. 이 suite는 Kubernetes 배포용 MySQL init script, TLS
`VERIFY_IDENTITY`, runtime 계정의 DML-only 권한과 migration-only runner도 함께 검증한다.
TLS hostname 검증용 test alias를 loopback에 고정하므로 로컬 Docker socket 또는 일반
GitHub runner를 기준으로 하며, 원격 `DOCKER_HOST`는 현재 지원하지 않는다.

```bash
./gradlew --no-daemon :bootstrap:mysqlTest
```

CI의 필수 검증 job은 일반 테스트, 패키징된 guard 도구의 fail-closed 실행, 운영 이미지와
MySQL 통합 테스트를 모두 검증한다.

## MVP 링크 생성

생성 intent마다 UUID를 한 번 만들고 재시도에도 같은 `Idempotency-Key`를 사용한다.

```bash
curl -i http://localhost:8080/api/v1/links \
  -H 'Authorization: Bearer <management-token>' \
  -H 'Idempotency-Key: 8e448211-66ae-44ab-9888-c4960648c22b' \
  -H 'Content-Type: application/json' \
  --data '{"targetSystem":"ROUND","targetPath":"/room/abcd-efgh-jkmn","purpose":"MEETING_ENTRY"}'
```

최초 요청은 `201`, 같은 키와 payload의 재시도는 동일한 short URL과 `200`을 반환한다.
같은 키를 다른 payload에 사용하면 `409`로 거부한다.

## 문서

- [제품 기준선](docs/PRD/0001_product-baseline/spec.md)
- [API 계약](docs/PRD/0002_api-contract/spec.md)
- [BATON·ROUND 교차 서비스 링크 계약](docs/PRD/0003_cross-service-link-contract/spec.md)
- [Private Kubernetes DB 토폴로지](docs/ADR/0008_private-kubernetes-database-topology/adr.md)
- [Private Kubernetes 배포 runbook](docs/RUNBOOK/kubernetes-private-server-deployment.md)
- [마이크로서비스 경계](docs/ADR/0001_microservice-boundary/adr.md)
- [링크 보안 모델](docs/ADR/0002_link-security/adr.md)
- [멱등한 링크 생성](docs/ADR/0003_idempotent-link-creation/adr.md)
- [멱등 생성의 공개 origin 보존](docs/ADR/0009_idempotent-public-origin-replay/adr.md)
- [링크 코드 HMAC 키와 DB 결합](docs/ADR/0004_link-code-key-binding/adr.md)
- [Typed target locator](docs/ADR/0005_trusted-target-locator/adr.md)
- [계약 전 target 정리](docs/ADR/0006_target-contract-remediation/adr.md)
- [MySQL 절대 시각 저장 형식](docs/ADR/0007_mysql-instant-storage/adr.md)
- [기존 DB HMAC guard 최초 결합 runbook](docs/RUNBOOK/link-code-key-guard-binding.md)
- [Target contract v1 정리 runbook](docs/RUNBOOK/target-contract-v1-remediation.md)
