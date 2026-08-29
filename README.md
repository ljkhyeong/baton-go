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
- 발급자 서명 JWT와 작업별 scope로 보호하는 `/api/v1/links` 생성·조회·폐기 API
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

교차 서비스 계약 v1의 허용 대상, 권한 소유, 동일 출처 경계와 운영
차단 조건은 [교차 서비스 링크 계약](docs/PRD/0003_cross-service-link-contract/spec.md)이
정본이다. GO는 생성과 공개 해석 양쪽에서 해당 계약을 안전 차단 방식으로
강제하고, 비허용 저장 대상은 원문을 노출하지 않은 채 `404 LINK_NOT_FOUND`로
숨긴다. 이 구현만으로 BATON·ROUND 권한과 실제 경계 검증이 완료되지는 않는다.

계약 강화 전 저장 데이터는 기본 비활성화된 비공개 운영 API로 조사하고,
원본 도메인 소유자가 승인한 링크만 폐기·재발급한다. 활성화 조건, 민감 필드
비노출 및 완료 판정은 [대상 계약 v1 정리 실행서](docs/RUNBOOK/target-contract-v1-remediation.md)를
따른다. 로컬 기본 출처는 개발용이며 운영 출처·라우팅·쿠키 계약은 정본의
종단 간 검증을 통과해야 한다.

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

관리 API는 `BATON_GO_MANAGEMENT_JWT_ISSUER_URI`의 발급자가 서명한 JWT를 사용한다. 운영 발급자
URI는 HTTPS이며 JWT의 `aud`는 기본 `baton-go`와 일치해야 한다. 다른 audience가 필요하면
`BATON_GO_MANAGEMENT_JWT_AUDIENCE`를 명시한다. Spring Boot 표준
`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI`에는 발급자의 JWK Set 주소를 설정한다.
이 설정은 시작할 때 discovery 서버에 의존하지 않으면서 `iss` 검증을 유지한다. 링크
생성·조회·폐기에는 각각 `baton-go.links.create`,
`baton-go.links.read`, `baton-go.links.revoke` scope가 필요하다.

`BATON_GO_LINK_CODE_SECRET`은 32자 이상의 별도 무작위 값이어야 한다. 기존 DB-키 결합과 복구
호환성을 위해 길이 외의 문법을 추가 제한하거나 공백 제거·Unicode 정규화하지 않고 설정 문자열
그대로 사용한다. 서버 설정은 Spring Boot의 표준 외부 설정 우선순위와 바인딩을 그대로 사용한다.
새 비밀값은 base64url 또는 hex처럼 셸, dotenv와 Spring 자리 표시자 문법에 걸리지 않는 문자
집합으로 생성하고 로그나 명령행에 출력하지 않는다. `.env.example`의 짧은 `REPLACE_ME_TOO` 값은
최소 길이 검증에서 바로 실패하므로 실제 값으로 교체해야 한다. 링크 코드 파생 비밀값은 재시작과
복구 뒤에도 같은 값을 유지해야 기존 생성 요청을 동일 URL로 재생할 수 있다.

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

신규 빈 DB는 현재 파생 버전과 HMAC 키 지문에 자동 결합하지만, 기존 데이터가 있는
미결합 DB는 안전하게 실패한다. DB 백업과 해당 시점의
`BATON_GO_LINK_CODE_SECRET` 버전을 하나의 복구 단위로 관리하고, 키 묶음 없이
비밀값을 단독 회전하지 않는다. 정책 정본은
[ADR-0004](docs/ADR/0004_link-code-key-binding/adr.md)이다.

기존 DB의 최초 결합은 모든 쓰기를 중지하고 분리된 `guard-tool`로 카나리를
검증한 뒤에만 수행한다. 직접 SQL이나 임의 비밀값 강제 결합 대신
[기존 데이터베이스 HMAC 보호 장치 최초 결합 실행서](docs/RUNBOOK/link-code-key-guard-binding.md)를
따른다.

`.env`는 Docker Compose의 dotenv 문법으로 해석하는 데이터 파일이며 셸 스크립트가 아니다.
겉보기에는 `KEY=VALUE` 형식이어도 `source ./.env`로 읽으면 셸이 명령 치환, 변수 확장과
백틱을 실행해 자격 증명 값을 바꾸거나 명령으로 실행할 수 있다.

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

`MYSQL_ROOT_HOST=localhost`는 새 MySQL 데이터 디렉터리를 초기화할 때만 적용된다. 기존
`baton_go_mysql_data` 볼륨을 사용하는 환경은 `mysql.user`에서 `root@'%'` 존재 여부를
확인하고 로컬 관리자 계정의 접근을 검증한 뒤 해당 원격 root 계정을 수동 폐기한다.
이 점검을 위해 기존 볼륨을 삭제하지 않는다.

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

Gradle은 `gradle/verification-metadata.xml`의 SHA-256으로 내려받은 의존성을 검증한다.
의존성을 변경할 때는 검증 메타데이터를 삭제하거나 검증을 끄지 말고, 새 아티팩트의 출처와
체크섬을 검토한 뒤 같은 변경에서 메타데이터를 갱신한다.

Flyway/JPA와 동시 생성 동작을 포함한 MySQL 통합 검증은 Docker가 실행 중인 환경에서
별도로 수행한다. 이 테스트 묶음은 Kubernetes 배포용 MySQL 초기화 스크립트, TLS
`VERIFY_IDENTITY`, 실행 계정의 DML 전용 권한과 마이그레이션 전용 실행기도 함께 검증한다.
TLS 호스트 이름 검증용 테스트 별칭을 루프백에 고정하므로 로컬 Docker 소켓 또는 일반
GitHub 실행기를 기준으로 하며, 원격 `DOCKER_HOST`는 현재 지원하지 않는다.

```bash
./gradlew --no-daemon :bootstrap:mysqlTest
```

## MVP 링크 생성

생성 의도마다 UUID를 한 번 만들고 재시도에도 같은 `Idempotency-Key`를 사용한다.

```bash
curl -i http://localhost:8080/api/v1/links \
  -H 'Authorization: Bearer <management-jwt>' \
  -H 'Idempotency-Key: 8e448211-66ae-44ab-9888-c4960648c22b' \
  -H 'Content-Type: application/json' \
  --data '{"targetSystem":"ROUND","targetPath":"/room/abcd-efgh-jkmn","purpose":"MEETING_ENTRY"}'
```

최초 요청은 `201`, 같은 키와 요청 내용의 재시도는 동일한 단축 URL과 `200`을 반환한다.
같은 키를 다른 요청 내용에 사용하면 `409`로 거부한다.

## 문서

### 제품과 HTTP 계약

- [제품 기준선](docs/PRD/0001_product-baseline/spec.md)
- [API 계약](docs/PRD/0002_api-contract/spec.md)
- [테스트에서 생성하는 HTTP 계약 조각](docs/API/rest-docs.md)
- [BATON·ROUND 교차 서비스 링크 계약](docs/PRD/0003_cross-service-link-contract/spec.md)

### 장기 설계 결정

- [마이크로서비스 경계](docs/ADR/0001_microservice-boundary/adr.md)
- [링크 보안 모델](docs/ADR/0002_link-security/adr.md)
- [멱등한 링크 생성](docs/ADR/0003_idempotent-link-creation/adr.md)
- [링크 코드 HMAC 키와 DB 결합](docs/ADR/0004_link-code-key-binding/adr.md)
- [형식화한 대상 위치 식별자](docs/ADR/0005_trusted-target-locator/adr.md)
- [계약 전 대상 정리](docs/ADR/0006_target-contract-remediation/adr.md)
- [MySQL 절대 시각 저장 형식](docs/ADR/0007_mysql-instant-storage/adr.md)
- [비공개 Kubernetes DB 구성](docs/ADR/0008_private-kubernetes-database-topology/adr.md)
- [멱등 생성의 공개 출처 보존](docs/ADR/0009_idempotent-public-origin-replay/adr.md)
- [관리 API의 발급자 서명 JWT 인증](docs/ADR/0010_management-jwt-authentication/adr.md)

### 운영 절차

- [비공개 Kubernetes 배포 실행서](docs/RUNBOOK/kubernetes-private-server-deployment.md)
- [기존 DB HMAC 보호 장치 최초 결합 실행서](docs/RUNBOOK/link-code-key-guard-binding.md)
- [대상 계약 v1 정리 실행서](docs/RUNBOOK/target-contract-v1-remediation.md)
