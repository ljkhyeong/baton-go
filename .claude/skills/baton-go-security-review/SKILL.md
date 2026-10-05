---
name: baton-go-security-review
description: BATON GO 변경을 프로젝트 보안 불변식(로그·오류 응답의 민감값, 대상 경로 허용 목록, 공개 조회 무변경, 코드·멱등성 키 해시 저장, 관리 JWT scope, 배포 노출 범위)으로 검토할 때 사용한다. 보안 검토 요청이나 인증·로그·대상 검증·키·배포 노출을 바꾼 변경에 사용한다.
argument-hint: "[시작 리비전 | 경로]"
---

# BATON GO 보안 불변식 검토

일반 취약점 목록보다 이 서비스가 지켜야 하는 규칙의 위반을 찾는다. 기준은 `AGENTS.md`의 구현 규칙,
`docs/ADR/0002_link-security/adr.md`, `docs/ADR/0010_management-jwt-authentication/adr.md`,
`docs/PRD/0003_cross-service-link-contract/spec.md`다.

## 범위

- 인자가 리비전이면 `git diff <리비전>` + 미추적 파일, 경로면 해당 경로, 없으면 미커밋 변경을 본다.
- 읽기 전용으로 판단한다. 사용자가 요청하지 않으면 수정하지 않는다.

## 점검 항목

### 1. 민감값 노출

민감값: 멱등성 키, 원문 링크 코드, 전체 단축 URL, 대상 경로, HMAC 비밀값, JWT·Bearer 토큰, BATON 접근 키,
ROUND 참여 허가, 세션 ID, DB 비밀번호, 웹훅 URL.

- 새 로그 호출, 예외 메시지, `@Value`·설정 바인딩 실패 메시지, 메트릭 태그에 민감값이 들어가지 않는가.
- 민감값을 가진 새 타입은 `toString()`을 재정의해 원문을 숨기는가. Java `record`의 기본 `toString()`은 모든 필드를 출력한다.
  기존 예: `CreateLinkRequest`, `CreationIdempotencyKey`, `LinkCodeProperties`.
- 오류 응답은 `UPPER_SNAKE_CASE` 코드·사용자 메시지·선택적 `requestId`만 담고 요청 원문 값을 되돌려주지 않는가.
- 허용되지 않은 저장 대상은 원문 없이 `404 LINK_NOT_FOUND`로 숨기는가. 없는 링크와 응답이 구별되지 않는가.

### 2. 대상 경로

- 대상은 `TrustedTargetPolicy`의 시스템·목적별 정규식 허용 목록과 `matches()` 전체 일치로만 허용한다.
  `startsWith`·`contains` 같은 부분 검사나 거부 목록으로 바꾸지 않는다.
- 스킴, 호스트, 쿼리, 프래그먼트, `//`가 거부되는가. 퍼센트 인코딩(`%2F%2F`, `%5C`), 역슬래시, `..`, 공백·제어 문자로
  허용 목록을 우회할 수 없는가. 새 경로 형식은 거부 테스트와 함께 추가됐는가.
- 저장된 대상도 공개 조회 시 다시 검사하는가. 대상 URL 조립(`adapter-out-external`)이 설정된 출처 외의 호스트를 만들지 않는가.

### 3. 공개 조회

- `GET·HEAD /l/{code}`가 DB 상태·사용 횟수·이력을 바꾸지 않는가. 상태 변경은 인증된 별도 `POST`인가.
- 요청 제한이 DB 조회 전에 적용되고, 클라이언트 IP·`X-Forwarded-*`를 키로 쓰지 않는가.
- HTML 안내 화면의 값은 이스케이프되고 기존 보안 헤더(`Content-Security-Policy`, `X-Content-Type-Options`,
  `Referrer-Policy`)와 캐시 정책이 유지되는가.

### 4. 생성·코드·저장

- 신규 생성은 정규 UUID `Idempotency-Key`를 요구하고, 같은 키의 다른 요청을 `409`로 거부하는가.
- 공개 코드는 32자 이상 비밀값의 HMAC-SHA-256에서 128비트를 파생하는가. 난수·짧은 코드·사용자 지정 코드로 바꾸지 않는가.
- DB에는 코드·멱등성 키의 SHA-256 해시만 저장하는가. 새 컬럼·캐시·Redis 키에 원문이 들어가지 않는가.
- 재시도는 저장된 키 버전과 공개 출처를 쓰고, 값이 없으면 현재 설정으로 대체하지 않는가.

### 5. 관리 API

- 새 `/api/v1` 엔드포인트가 `ManagementApiSecurityConfiguration`에서 작업별 scope(`baton-go.links.create`·`read`·`revoke` 등)를
  요구하는가. 기본 허용(`permitAll`)이나 넓은 경로 패턴으로 다른 엔드포인트가 열리지 않는가.
- `iss`·`aud`·`exp`·공백 아닌 `sub` 검증이 유지되는가. 발급자·JWK 주소의 HTTP 허용이 루프백 개발 환경으로 한정되는가.

### 6. 배포·공급망

- Actuator는 `8081`에만 있고, `/livez`·`/readyz`는 Ingress에 노출되지 않는가. 공개 Ingress는 `/l`, 비공개는 `/api/v1`만 받는가.
- 런타임 DB 계정은 DML 전용이고 TLS `VERIFY_IDENTITY`를 유지하는가. DDL 자격 증명은 마이그레이션 Job에만 있는가.
- 비밀값·환경별 주소는 환경 변수·Secret으로 주입되고 저장소 파일에 실제 값이 없는가.
- 액션은 커밋 SHA, 이미지는 digest로 고정되고 Gradle 검증 메타데이터가 유지되는가.
- BATON·ROUND 권한 판단을 GO로 옮기거나 GO 링크만으로 권한을 부여하지 않는가.

## 보고

발견 사항만 심각도 순으로 보고한다. 각 항목에 `파일:줄`, 위반한 불변식, 구체적 실패 시나리오(입력 → 결과),
수정 방향을 적는다. 추정이면 확인한 범위와 함께 "추정"으로 표시하고, 문제가 없으면 확인한 항목만 짧게 나열한다.
