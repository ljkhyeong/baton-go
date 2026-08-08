# ADR-0002: 링크 코드와 대상 보안 모델

- 상태: 채택
- 결정일: 2026-07-29

## 결정

- 공개 코드는 별도 32자 이상 비밀과 canonical UUID 멱등성 키를 HMAC-SHA-256으로
  결합하고 앞 16 byte를 Base64 URL-safe no-padding으로 표현한다. 기존 DB-key 결합과
  복구 호환성을 위해 길이 외의 문법을 추가 제한하거나 trim·Unicode 정규화하지 않고
  설정 문자열 그대로 사용한다.
- DB에는 원문 코드가 아니라 SHA-256 lowercase hex 해시만 저장한다.
- DB에는 원문 멱등성 키도 저장하지 않고 SHA-256 lowercase hex 해시만 저장한다.
- 코드 입력은 정확한 URL-safe 형식과 길이를 검증한 뒤 해시한다.
- 대상은 `BATON`, `ROUND` enum과 안전한 target path로 나눈다.
- target path에는 scheme, authority, query, fragment, backslash, 제어문자와 `//` prefix를
  허용하지 않는다.
- base URL은 환경 설정으로만 제공하고 HTTP 또는 HTTPS origin이어야 한다.
- 리다이렉트 응답은 `no-store`와 `no-referrer`를 사용한다.
- 관리 API는 공백 없는 printable ASCII로 구성한 최소 32자의 환경 변수 Bearer credential로
  보호한다. 이 자격은 파일럿용 서비스 인증이며 최종 사용자 신원 모델이 아니다.
- credential이 담긴 Compose dotenv는 Compose parser가 읽는 데이터 파일로만 취급하고 셸에서
  `source`하지 않는다. 호스트 실행은 secret manager나 IDE가 process environment에 직접
  주입해 parser 변환이나 셸 확장 없이 설정 문자열을 보존한다.
- HMAC 비밀, 관리 credential과 DB password는 Spring placeholder가 다시 해석하지 않는 raw
  process environment 경계에서 읽는다. `${...}`, backslash와 공백을 포함한 값도 각 credential
  자체의 문법 검증 전까지 원문 바이트를 보존하며 서버와 guard-tool이 같은 값을 사용한다.
  해당 process environment 값이 존재하면 command line, JVM system property와
  `SPRING_APPLICATION_JSON`의 동일 canonical property보다 우선한다.
- raw 보존 경계는 Compose dotenv parsing 이후의 process environment다. dotenv 값 자체에
  literal `${...}`가 필요하면 single-quoted value로 interpolation을 막고, server와 guard-tool에
  동일한 parsing 결과를 주입한다.
- token, access key와 전체 Authorization 값을 로그에 기록하지 않는다.
- 멱등성 키, 링크 코드 파생 비밀과 전체 short URL을 로그에 기록하지 않는다.

## 시간 경계

- `notBefore == now`이면 활성이다.
- `expiresAt == now`이면 만료다.
- `expiresAt`은 생성 시각과 `notBefore`보다 뒤여야 한다.
- 폐기는 멱등이며 최초 폐기 시각을 보존한다.

## 인증된 admission과 향후 redemption

초대·회의 입장은 공개 `GET` 리다이렉트와 분리한다. `GET`은 landing 또는 resolution만
수행한다. BATON v1 navigation은 기존 access key를 보유한 브라우저의 복귀만 지원하며
session이나 claim을 새로 발급하지 않는다. ROUND participation grant만 BATON의 인증된
`POST`에서 발급한다. 선행 `GET /api/v1/auth/session`은 기존 session과 CSRF 상태 조회다.

PRD-0003의 현재 ROUND 계약은 BATON session·CSRF 뒤 갱신하는 짧은 수명의 participation
grant이며 one-time 사용을 보장하지 않는다. 진정한 일회성 redemption이 필요하면 원자적
소비 저장소와 별도 인증 `POST` 계약을 추가한다.
