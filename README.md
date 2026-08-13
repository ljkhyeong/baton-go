# BATON GO

BATON GO는 단순 URL 축약기가 아니라 BATON과 ROUND를 위한 정책형 링크 게이트웨이다.
공개 링크 코드의 수명주기와 신뢰된 대상 라우팅을 담당하되, 각 제품의 최종 접근 권한은
소유 서비스가 계속 판단한다.

## 첫 구현 범위

- 정규 UUID 멱등성 키와 HMAC 기반 128비트 공개 코드 발급
- 원문 코드 대신 SHA-256 해시 저장
- `BATON`, `ROUND`의 v1 정확한 대상 조합과 정규 위치 식별자만 생성·해석
- 시작 시각, 만료 시각과 즉시 폐기
- 공개 `GET·HEAD /l/{code}` 리다이렉트
- 관리용 `/api/v1/links` 생성·조회·폐기 API
- MySQL/Flyway 영속화, 상태 확인과 Prometheus 엔드포인트

다음은 아직 구현 범위가 아니다.

- BATON 접근 키를 대체하는 계정·초대 권한
- ROUND WebSocket 입장용 BATON 참여 허가 발급 엔드포인트
- 임의 외부 URL 축약
- 사용자 지정 별칭, 클릭 분석, Redis 요청률 제한
- 링크 소비 횟수와 일회성 교환

## 서비스 원칙

```text
BATON GO      코드·시간·폐기·신뢰 대상 라우팅
BATON         작업 공간·역할·회차·자료 권한
ROUND         방 입장·피어 식별·TURN 발급 권한
```

BATON의 `#accessKey`나 ROUND 참여 허가를 GO의 URL, DB 또는 로그에 넣지 않는다.
현재 GO의 BATON 위치 식별자는 해당 팀의 검증된 접근 키를 이미 저장한 브라우저가
작업 공간으로 복귀할 때만 사용한다. 신규 브라우저는 기존 BATON 공유 링크를 계속 사용하며,
향후 계정 기반 초대·claim 계약이 생기기 전에는 GO 링크만으로 권한을 부여하지 않는다.

교차 서비스 계약 v1은 정확히 두 위치 식별자만 승인한다.

```text
BATON + NAVIGATION    /teams/{teamId}/seasons/{seasonId}
ROUND + MEETING_ENTRY /room/{roomId}
```

ROUND 경로는 입장 전 도착 페이지일 뿐 입장 권한이 아니다. BATON 세션과 CSRF 확인 뒤 발급되는
짧은 수명의 참여 허가가 실제 입장을 통제한다. 정확한 식별자 문법, 엔드포인트,
쿠키와 현재 운영 차단 조건은 [교차 서비스 링크 계약](docs/PRD/0003_cross-service-link-contract/spec.md)을
따른다. GO는 생성과 공개 해석 양쪽에서 이 정확한 조합을 안전 차단 방식으로 강제한다. 저장된
비허용 대상이나 알 수 없는 열거형도 리다이렉트하지 않고 `404 LINK_NOT_FOUND`로 숨긴다. 이때
`baton.go.public.resolver.target.contract.violations` 지표를 증가시키고 `linkId`와 `requestId`만
포함한 안전한 로그를 남기며 공개 코드, 대상 경로와 전체 단축 URL은 기록하지 않는다.

정확한 대상 정책 구현만으로 공개 운영 준비가 끝난 것은 아니다. 계약 강화 전 저장
데이터를 조사해 비허용 링크를 폐기·재발급해야 하며, PRD-0003의 BATON 세션·허가,
권위 있는 방 mapping, 동일 출처 경계, 호출자 트랜잭셔널 outbox 등 나머지 관문도 모두 통과해야 한다.

### 계약 전 데이터 조사와 정리

이전 형식 대상 정리는 자동 일괄 작업이 아니다. 기본 비활성화된 운영 API로 원문 행을
안전한 부가 정보만 포함해 조사하고, 원본 도메인 소유자가 승인한 링크 ID를 버전과 함께
한 건씩 재검증·폐기한다. API 응답에는 대상 경로, 원문 열거형, 코드 해시, 단축 URL과
멱등성 해시가 포함되지 않는다.

점검 시간에는 비공개 Ingress에서만 다음 값을 잠시 활성화한다.

```text
BATON_GO_TARGET_CONTRACT_OPERATIONS_ENABLED=true
BATON_GO_TARGET_CONTRACT_OPERATIONS_PRIVATE_INGRESS_CONFIRMED=true
```

두 번째 값은 공개 경계에서 해당 경로가 차단됐다는 사전 점검 증거를 확인한 뒤에만
설정한다. 확인 표시 자체가 네트워크 경계를 만들지는 않는다. 둘 중 하나라도 `false`이면
운영 컨트롤러는 등록되지 않는다.

재발급은 GO가 이전 형식 경로를 교정해서 수행하지 않는다. BATON 또는 ROUND의 원본 도메인 집합체
소유자가 권위 있는 연결 정보와 새 정규 UUID 의도로 정상 생성 API를 호출한다. 전체
절차와 완료 조건은
[대상 계약 v1 정리 실행서](docs/RUNBOOK/target-contract-v1-remediation.md)를 따른다.
운영 기능 구현과 테스트 DB 검증만으로 배포 DB 조사 관문을 완료 처리하지 않는다.

BATON 모드 운영에서는 `BATON_GO_BATON_BASE_URL`과 `BATON_GO_ROUND_BASE_URL`을 같은 BATON
공개 HTTPS 출처로 설정한다. 경계가 `/room/**`·`/round-ui/**`는 ROUND 웹으로,
참여 허가 갱신은 BATON으로, 시그널링·TURN만 ROUND 내부 서비스로 라우팅한다.
로컬의 서로 다른 `5173`·`5174` 기본값은 이 종단 간 운영 계약을 만족하지 않는다.
두 대상이 모두 루프백이면 서로 다른 HTTP 포트를 로컬 개발 예외로 허용한다. 하나라도
비로컬이면 애플리케이션은 두 값을 동일한 HTTPS 출처로 검증하고 시작 단계에서
안전하게 차단한다. 이 설정 검증은 실제 경계 경로·쿠키·헤더 E2E 검증을 대신하지 않는다.
루프백은 `localhost`, 선행 0이 없는 정규 점-십진 표기 IPv4 `127.0.0.0/8`과 IPv6
루프백 리터럴로 판정하며, 명시적 출처 포트는 `1..65535`만 허용한다.

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
무작위 값이어야 한다. 관리 자격 증명은 HTTP 헤더에 안정적으로 제시할 수 있도록 공백 없는
출력 가능 ASCII만 사용한다. 링크 코드 파생 비밀값은 기존 DB-키 결합과 복구 호환성을 위해
길이 외의 문법을 추가 제한하거나 공백 제거·Unicode 정규화하지 않고 설정 문자열 그대로 사용한다.
서버 설정은 Spring Boot의 표준 외부 설정 우선순위와 바인딩을 그대로 사용한다. 새 비밀값은
base64url 또는 hex처럼 셸, dotenv와 Spring 자리 표시자 문법에 걸리지 않는 문자 집합으로
생성하고 자격 증명을 로그나 명령행에 출력하지 않는다. `.env.example`의 짧은 `REPLACE_ME`
값은 기존 최소 길이 검증에서 바로 실패하므로 실제 값으로 교체해야 한다. 두 비밀을 같게
설정해도 시작하지 않는다. 링크 코드 파생 비밀값은 재시작과 복구 뒤에도 같은 값을 유지해야
기존 생성 요청을 동일 URL로 재생할 수 있다.

`BATON_GO_PUBLIC_BASE_URL`도 모든 실행 환경에서 명시한다. 로컬 개발의 루프백 HTTP는
허용하지만, 사용자에게 반환되는 비로컬 단축 URL 출처는 HTTPS여야 한다. 값은 경로, 쿼리,
프래그먼트나 사용자 정보가 없는 출처여야 한다. 스킴·호스트 대소문자, 기본 포트와 루트 슬래시는
정규 출처로 정규화해 생성 예약에 저장한다. 같은 의도를 재생할 때는 현재 설정이
아니라 최초 예약의 출처를 사용하므로 출처 이전 뒤에도 같은 단축 URL을 반환한다. V5 이전
예약처럼 출처 증거가 없으면 현재 값으로 추정하지 않고 운영 오류로 실패한다.

비로컬 공개 기본 URL을 사용하면서 BATON·ROUND 대상 환경 변수를 생략해 localhost 기본값이
남은 설정은 시작 단계에서 거부한다. 로컬 기본값은 루프백 공개 출처를 사용하는 개발에만
허용되며 운영 배포는 세 출처를 모두 명시한다.

### HMAC 키와 데이터베이스 결합

애플리케이션은 시작할 때 링크 코드 HMAC 파생 버전과 키 지문을 DB의 단일 식별값과
대조한다. 신규 DB처럼 링크와 생성 예약이 모두 비어 있으면 현재 비밀값에 자동
결합한다. 이미 결합된 DB와 식별값이 다르거나 기존 데이터가 있는데 식별값이 비어 있으면
준비 상태를 열지 않고 시작에 실패한다. 생성 API도 예약 행을 쓰기 전에 같은 검증을 수행한다.

DB 백업과 그 시점의 `BATON_GO_LINK_CODE_SECRET` 비밀값 관리 버전은 하나의 복구
단위로 보관한다. DB만 또는 비밀값만 따로 복구하지 않는다. 복구 뒤에는 시작 검증과 보관된
카나리 생성 의도의 동일 URL 재생을 확인한다. 키 묶음을 도입하기 전에는 비밀값을 회전하지
않으며 지문을 로그나 운영 티켓에 기록하지 않는다.

기존 링크가 있는 DB에 이 보호 장치를 처음 도입할 때는 자동 결합하지 않는다. 모든 쓰기 작업을
중지하고 기존 비밀값 버전 및 카나리 코드 해시를 오프라인으로 검증한 뒤, 검증된 배포
절차로 단일 식별값을 한 번 결합한다. 카나리나 검증된 비밀값을 복구할 수 없으면 임의
비밀값으로 강제 결합하지 않는다. 자세한 결정은
[ADR-0004](docs/ADR/0004_link-code-key-binding/adr.md)를 따른다.

이 저장소는 일반 애플리케이션과 분리된 `guard-tool` 모듈의
`baton-go-guard-binding.jar`를 제공한다. 도구는 카나리 `Idempotency-Key`를 표준 입력으로만
받고 저장된 예약·링크의 해시를 검증한 뒤 한 트랜잭션에서 결합한다. 직접 SQL이나 우회
환경 변수 대신
[기존 데이터베이스 HMAC 보호 장치 최초 결합 실행서](docs/RUNBOOK/link-code-key-guard-binding.md)를
따른다.

`.env`는 Docker Compose의 dotenv 문법으로 해석하는 데이터 파일이며 셸 스크립트가 아니다.
겉보기에는 `KEY=VALUE` 형식이어도 `source ./.env`로 읽으면 셸이 명령 치환, 변수 확장과
백틱을 실행해 자격 증명 값을 바꾸거나 명령으로 실행할 수 있다. 예시 파일의 JDBC URL
따옴표도 Compose 구문 분석기 문법이므로 셸 호환성을 뜻하지 않는다.

호스트에서 Gradle로 애플리케이션을 실행할 때는 MySQL만 Compose로 먼저 실행하고,
애플리케이션에 필요한 `BATON_GO_*` 값은 비밀값 관리자의 프로세스 주입이나 IDE 실행
구성으로 Gradle 프로세스 환경에 직접 주입한다. Compose용 `.env`를 셸에서
`source`하거나 다른 dotenv 구문 분석기로 재해석하지 않는다.

```bash
docker compose --env-file .env up -d mysql

# 이 명령을 실행하는 프로세스 환경에는 비밀값 관리자나 IDE가 BATON_GO_* 값을 주입한다.
./gradlew :bootstrap:bootRun
```

애플리케이션과 MySQL을 모두 Compose로 실행하려면 다음 명령을 사용한다. 이 경로에서는
Compose가 자기 dotenv 문법으로 `.env`를 읽어 각 컨테이너에 주입한다.

```bash
docker compose --env-file .env up --build -d
docker compose --env-file .env ps
```

## 비공개 Kubernetes 배포

`deploy/k8s`에는 Kubernetes 기본 Kustomize로 조립하는 애플리케이션과 GO 전용 MySQL
매니페스트가 있다. MySQL은 BATON의 인스턴스·데이터베이스 사용자·PVC를 공유하지 않으며,
`baton_go` 데이터베이스와 별도 Secret·10Gi PVC를 사용한다. 장기 실행 애플리케이션은 DML 전용
계정만 받고, Flyway DDL 자격 증명은 컴포넌트 스캔 없는 일회성 마이그레이션 Job에만 주입한다.
MySQL은 보안 전송을 강제하고 애플리케이션과 Job은 `VERIFY_IDENTITY` 신뢰 저장소 계약을
사용한다. 특정 StorageClass, Ingress
컨트롤러와 TLS 발급 방식은 비공개 클러스터마다 다르므로 저장소 기본 구성에 고정하지 않는다.

네임스페이스는 워크로드와 분리해 먼저 적용하고, 실제 출처·불변 이미지와 외부 Secret을
준비한 뒤 `private-server` 오버레이를 적용한다.

```bash
kubectl kustomize deploy/k8s/bootstrap >/dev/null
kubectl kustomize deploy/k8s/overlays/private-server >/dev/null
```

실제 Secret 생성, 레지스트리 인증, 배포·백업·복구와 경계 구성은
[비공개 Kubernetes 배포 실행서](docs/RUNBOOK/kubernetes-private-server-deployment.md)를
따른다. 공개 Ingress는 `/l` 접두 경로만, 비공개 관리 경로는 `/api/v1` 접두 경로만 같은
HTTP Service로 분리해야 하며 Actuator `8081`은 기본 노출하지 않는다. 이 인프라 구성은
PRD-0003의 공개 운영 배포 관문을 대신하지 않는다.

애플리케이션 Ingress NetworkPolicy는 HTTP를 명시적으로 표시한 Ingress 네임스페이스에서만 받고,
Actuator는 표시한 모니터링 네임스페이스와 클라이언트 Pod 조합에만 허용한다. 실제 적용 전 CNI의
NetworkPolicy 집행과 kubelet 탐침 동작을 비공개 클러스터에서 검증한다.

기본 애플리케이션 포트는 `8080`, Actuator 관리 포트는 `8081`이다.
Docker는 Actuator 포트의 종합 `/actuator/health`를 계속 사용한다. 오케스트레이터 탐침은
`/actuator/health/liveness`와 `/actuator/health/readiness`를 사용하며, 준비 상태는 DB
연결 상태를 포함하지만 생존 상태는 포함하지 않는다.

공개 `GET·HEAD /l/{code}`에는 DB 조회 전 인스턴스 집계 요청률 제한 안전장치가
적용된다. `BATON_GO_PUBLIC_RESOLVER_RATE_LIMIT_CAPACITY`와
`BATON_GO_PUBLIC_RESOLVER_RATE_LIMIT_WINDOW`는 배포 트래픽에 맞춰 명시적으로 설정한다.
이 제한은 클라이언트 IP나 전달 헤더를 신뢰하지 않는 로컬 안전장치이므로, 여러 복제본을
공개할 때는 Ingress에서 별도의 분산 요청률 제한과 `/l/{code}` 접근 로그 가림을 적용한다.

## 검증

일반 빌드와 단위 테스트는 로컬 MySQL이나 Docker를 자동으로 요구하지 않는다.

```bash
./gradlew --no-daemon build
```

Flyway/JPA와 동시 생성 동작을 포함한 MySQL 통합 검증은 Docker가 실행 중인 환경에서
별도로 수행한다. 이 테스트 묶음은 Kubernetes 배포용 MySQL 초기화 스크립트, TLS
`VERIFY_IDENTITY`, 실행 계정의 DML 전용 권한과 마이그레이션 전용 실행기도 함께 검증한다.
TLS 호스트 이름 검증용 테스트 별칭을 루프백에 고정하므로 로컬 Docker 소켓 또는 일반
GitHub 실행기를 기준으로 하며, 원격 `DOCKER_HOST`는 현재 지원하지 않는다.

```bash
./gradlew --no-daemon :bootstrap:mysqlTest
```

CI의 필수 검증 Job은 일반 테스트, 패키징된 보호 도구의 안전 차단 실행, 운영 이미지와
MySQL 통합 테스트를 모두 검증한다.

## MVP 링크 생성

생성 의도마다 UUID를 한 번 만들고 재시도에도 같은 `Idempotency-Key`를 사용한다.

```bash
curl -i http://localhost:8080/api/v1/links \
  -H 'Authorization: Bearer <management-token>' \
  -H 'Idempotency-Key: 8e448211-66ae-44ab-9888-c4960648c22b' \
  -H 'Content-Type: application/json' \
  --data '{"targetSystem":"ROUND","targetPath":"/room/abcd-efgh-jkmn","purpose":"MEETING_ENTRY"}'
```

최초 요청은 `201`, 같은 키와 요청 내용의 재시도는 동일한 단축 URL과 `200`을 반환한다.
같은 키를 다른 요청 내용에 사용하면 `409`로 거부한다.

## 문서

- [제품 기준선](docs/PRD/0001_product-baseline/spec.md)
- [API 계약](docs/PRD/0002_api-contract/spec.md)
- [BATON·ROUND 교차 서비스 링크 계약](docs/PRD/0003_cross-service-link-contract/spec.md)
- [비공개 Kubernetes DB 구성](docs/ADR/0008_private-kubernetes-database-topology/adr.md)
- [비공개 Kubernetes 배포 실행서](docs/RUNBOOK/kubernetes-private-server-deployment.md)
- [마이크로서비스 경계](docs/ADR/0001_microservice-boundary/adr.md)
- [링크 보안 모델](docs/ADR/0002_link-security/adr.md)
- [멱등한 링크 생성](docs/ADR/0003_idempotent-link-creation/adr.md)
- [멱등 생성의 공개 출처 보존](docs/ADR/0009_idempotent-public-origin-replay/adr.md)
- [링크 코드 HMAC 키와 DB 결합](docs/ADR/0004_link-code-key-binding/adr.md)
- [형식화한 대상 위치 식별자](docs/ADR/0005_trusted-target-locator/adr.md)
- [계약 전 대상 정리](docs/ADR/0006_target-contract-remediation/adr.md)
- [MySQL 절대 시각 저장 형식](docs/ADR/0007_mysql-instant-storage/adr.md)
- [기존 DB HMAC 보호 장치 최초 결합 실행서](docs/RUNBOOK/link-code-key-guard-binding.md)
- [대상 계약 v1 정리 실행서](docs/RUNBOOK/target-contract-v1-remediation.md)
