# BATON GO

BATON GO는 단순 URL 축약기가 아니라 BATON과 ROUND를 위한 정책형 링크 게이트웨이다.
공개 링크 코드의 수명주기와 신뢰된 대상 라우팅을 담당하되, 각 제품의 최종 접근 권한은
소유 서비스가 계속 판단한다.

## 첫 구현 범위

- canonical UUID 멱등성 키와 HMAC 기반 128-bit 공개 코드 발급
- 원문 코드 대신 SHA-256 해시 저장
- `BATON`, `ROUND` 신뢰 대상과 상대 경로만 허용
- 시작 시각, 만료 시각과 즉시 폐기
- 공개 `GET /l/{code}` 리다이렉트
- 관리용 `/api/v1/links` 생성·조회·폐기 API
- MySQL/Flyway 영속화, health와 Prometheus endpoint

다음은 아직 구현 범위가 아니다.

- BATON access key를 대체하는 계정·초대 권한
- ROUND WebSocket 입장용 one-time ticket
- 임의 외부 URL 축약
- custom alias, 클릭 분석, Redis rate limit
- 링크 소비 횟수와 일회성 redemption

## 서비스 원칙

```text
BATON GO      코드·시간·폐기·신뢰 대상 라우팅
BATON         workspace·역할·회차·자료 권한
ROUND         room 입장·peer identity·TURN 발급 권한
```

BATON의 `#accessKey`나 향후 ROUND 입장 토큰을 GO의 URL, DB 또는 로그에 넣지 않는다.
현재 BATON 공유 링크는 신규 브라우저 접근에 계속 사용하고, GO는 검증된 workspace 안에서
링크를 열거나 향후 계정 기반 초대 교환을 시작하는 진입점으로 사용한다.

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

필수 환경 변수를 준비한다.

```bash
cp .env.example .env
```

관리 credential과 링크 코드 파생 비밀은 서로 다른 값으로 교체한다. 링크 코드 파생
비밀은 재시작과 복구 뒤에도 같은 값을 유지해야 기존 생성 요청을 동일 URL로 재생할 수
있다.

MySQL을 실행한 뒤 환경 변수를 로드하고 다음 명령을 사용한다.

```bash
./gradlew :bootstrap:bootRun
```

기본 애플리케이션 포트는 `8080`, 관리 포트는 `8081`이다.

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
- [마이크로서비스 경계](docs/ADR/0001_microservice-boundary/adr.md)
- [링크 보안 모델](docs/ADR/0002_link-security/adr.md)
