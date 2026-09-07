# ADR-0010: 관리 API의 발급자 서명 JWT 인증

- 상태: 채택
- 결정일: 2026-08-29

## 배경

초기 관리 API는 하나의 정적 Bearer 토큰을 모든 호출자가 공유했다. 이 방식은 구현은 작지만
호출자 신원과 작업별 권한을 구분할 수 없고, 회전할 때 Secret 변경과 전체 Pod 재시작이
필요하다. 토큰 문법 분석, 상수 시간 비교와 설정 검증도 BATON GO가 직접 소유했다.

BATON GO는 브라우저 사용자가 아니라 신뢰된 서버 호출자가 사용하는 관리 API를 제공한다.
따라서 사용자 로그인 기능을 추가하지 않고 발급자가 인증한 서비스 신원과 작업 scope만
검증하면 된다.

## 결정

- `/api/v1/**`는 Spring Security OAuth2 Resource Server의 JWT 인증으로 보호한다.
- `BATON_GO_MANAGEMENT_JWT_ISSUER_URI`는 신뢰할 발급자 식별자로 필수다. Spring Boot 표준
  `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI`에는 발급자의 HTTPS JWK Set 주소를
  명시해 애플리케이션 시작을 metadata discovery 서버 가용성과 분리한다. 애플리케이션은 두
  endpoint의 비로컬 HTTP 설정을 시작 단계에서 거부하며 loopback HTTP는 로컬 개발에서만
  허용한다.
- JWT의 `iss`는 설정한 발급자, `aud`는 기본 `baton-go`와 일치해야 한다. 다른 audience가
  필요하면 `BATON_GO_MANAGEMENT_JWT_AUDIENCE`로 명시한다. 최종 바인딩된 audience 목록이
  비어 있거나 빈 값·공백뿐인 항목을 포함하면 시작을 거부한다. Spring Boot는 빈 목록이면
  audience 검증을 생략하므로 설정 단계에서 이를 차단하며, JWT의 `aud` 검증 자체는 계속
  Spring에 맡긴다.
- `exp`는 필수다. Spring 표준 `JwtTimestampValidator`의
  `setAllowEmptyExpiryClaim(false)`를 사용하고 별도 claim 검증기를 만들지 않는다.
  이 검증기를 빈으로 등록해 Spring Boot 자동 설정이 기존 시간 검증 대신 사용하게 한다.
  서명·발급자·audience 검증과 JWK 조회·캐시는 유지한다. `nbf`는 계속 선택 사항이며
  시계 오차 허용 범위도 Spring 기본값을 유지한다.
- JWK HTTP 대기 시간은 Spring Boot의 `JwkSetUriJwtDecoderBuilderCustomizer`로 조정한다.
  Nimbus가 받는 `RestOperations`에 Spring의 `RestTemplate`과
  `SimpleClientHttpRequestFactory`를 연결하고, JWT 디코더·검증기·JWK 캐시는 교체하지 않는다.
  별도 HTTP 클라이언트, 재시도나 시간 제한 실행기를 만들지 않는다. 기본값과 운영 설정은
  [관리 JWK 대기 시간](../../RUNBOOK/kubernetes-private-server-deployment.md#관리-jwk-대기-시간)을 따른다.
- 경로별 필요한 scope는 다음과 같다.

| 작업 | scope |
| --- | --- |
| 링크 생성 | `baton-go.links.create` |
| 링크 관리 조회 | `baton-go.links.read` |
| 링크 폐기 | `baton-go.links.revoke` |
| 대상 계약 목록 조사·정리 | `baton-go.target-contract.operate` |

- 인증 누락·JWT 검증 실패는 `401 MANAGEMENT_AUTHENTICATION_REQUIRED`, 유효한 JWT에 scope가
  없으면 `403 MANAGEMENT_AUTHORIZATION_REQUIRED`로 응답한다. `401`에는
  `WWW-Authenticate: Bearer realm="baton-go-management"`를 포함한다.
- JWK 조회 등 인증 서비스 장애는 Spring의 인증 실패 처리기에서
  `500 INTERNAL_ERROR`와 공통 오류 본문으로 응답한다. `requestId`와 예외 종류만 기록하고
  JWT·원격 오류 응답·예외 원문은 기록하지 않는다. JWT 검증과 JWK 조회·캐시는 계속 Spring이
  수행한다.
- 인증 서비스 장애는 별도 Micrometer 카운터로 집계해 공개 링크 성공 트래픽과 무관하게
  감지한다. 경보와 수집 기준은 [Prometheus 운영 절차](../../RUNBOOK/prometheus-alerts.md)를 따른다.
- 관리 보안 체인은 세션을 만들지 않고 CSRF 상태를 사용하지 않는다. 공개 `/l/**`와 Actuator는
  이 체인에 포함하지 않으며 기존 네트워크 경계를 계속 적용한다.
- JWT 원문, `Authorization` 헤더와 토큰 claim 전체를 로그·지표·오류에 기록하지 않는다.
- 관리 쓰기 완료 이력은 검증된 JWT의 `sub`를 서비스 식별자로 사용한다. 기존 인증 조건을
  변경하거나 다른 claim으로 대체하지 않는다. 웹 경계에서 트랜잭션 서비스의 성공 반환 뒤
  허용한 필드만 기존 로그에 기록하며, 별도 감사 DB나 보존 기간을 만들지 않는다. 기록 범위,
  식별자 누락과 수집 실패의 의미는 [관리 작업 이력 운영 절차](../../RUNBOOK/management-operation-history.md)를 따른다.
- JWT 발급, 서비스 신원 등록, 개인 키 보관과 signing key 회전은 발급자가 소유한다. GO는
  JWK 공개키를 검증하며 정적 관리 토큰 Secret이나 자체 JWT 파서를 두지 않는다.

## 전환

1. 발급자에 `aud=baton-go`와 필요한 scope를 가진 서비스 신원을 먼저 준비하고 JWT에
   유효한 `exp`를 포함하도록 설정한다.
2. 비공개 경계에서 각 scope의 허용과 누락 scope의 `403`, `exp` 누락·만료의 `401`을 검증한다.
3. 호출자를 JWT로 전환한 뒤 정적 `BATON_GO_MANAGEMENT_TOKEN` 설정과
   `baton-go-management-credentials` Secret을 폐기한다.
4. 새 JWK를 먼저 게시하고 발급 키를 전환한 뒤 기존 JWT 최대 수명과 캐시 관찰 시간이 지난
   후에 이전 공개키를 제거하는 회전 훈련을 수행한다.

정적 토큰으로 되돌아가는 호환 모드는 두지 않는다. 발급자 장애가 있어도 이미 받은 JWT와
캐시한 검증 키가 유효한 동안은 검증할 수 있지만, 첫 키 조회나 새 `kid` 조회가 실패하면 관리
요청은 안전하게 실패한다. 단축 링크 접속 처리는 이 의존성의 영향을 받지 않는다.

## 결과

- 호출자와 작업 권한을 분리하고 발급자 감사 기록과 키 회전을 사용할 수 있다.
- 직접 구현한 Bearer 파서, 공유 토큰 비교, 관리 토큰 설정과 HMAC 비밀 분리 검사가 제거된다.
- 관리 경계에서 발급자 JWK HTTPS 접근과 캐시·회전 검증이 새 운영 책임이 된다.
