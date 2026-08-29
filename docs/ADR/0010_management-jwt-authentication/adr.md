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
  필요하면 `BATON_GO_MANAGEMENT_JWT_AUDIENCE`로 명시한다.
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
- 관리 보안 체인은 세션을 만들지 않고 CSRF 상태를 사용하지 않는다. 공개 `/l/**`와 Actuator는
  이 체인에 포함하지 않으며 기존 네트워크 경계를 계속 적용한다.
- JWT 원문, `Authorization` 헤더와 토큰 claim 전체를 로그·지표·오류에 기록하지 않는다.
- JWT 발급, 서비스 신원 등록, 개인 키 보관과 signing key 회전은 발급자가 소유한다. GO는
  JWK 공개키를 검증하며 정적 관리 토큰 Secret이나 자체 JWT 파서를 두지 않는다.

## 전환

1. 발급자에 `aud=baton-go`와 필요한 scope를 가진 서비스 신원을 먼저 준비한다.
2. 비공개 경계에서 각 scope의 허용과 누락 scope의 `403`을 검증한다.
3. 호출자를 JWT로 전환한 뒤 정적 `BATON_GO_MANAGEMENT_TOKEN` 설정과
   `baton-go-management-credentials` Secret을 폐기한다.
4. 새 JWK를 먼저 게시하고 발급 키를 전환한 뒤 기존 JWT 최대 수명과 캐시 관찰 시간이 지난
   후에 이전 공개키를 제거하는 회전 훈련을 수행한다.

정적 토큰으로 되돌아가는 호환 모드는 두지 않는다. 발급자 장애가 있어도 이미 받은 JWT와
캐시한 검증 키가 유효한 동안은 검증할 수 있지만, 첫 키 조회나 새 `kid` 조회가 실패하면 관리
요청은 안전하게 실패한다. 공개 링크 해석은 이 의존성의 영향을 받지 않는다.

## 결과

- 호출자와 작업 권한을 분리하고 발급자 감사 기록과 키 회전을 사용할 수 있다.
- 직접 구현한 Bearer 파서, 공유 토큰 비교, 관리 토큰 설정과 HMAC 비밀 분리 검사가 제거된다.
- 관리 경계에서 발급자 JWK HTTPS 접근과 캐시·회전 검증이 새 운영 책임이 된다.
