# ADR-0002: 링크 코드와 대상 보안 모델

- 상태: 채택
- 결정일: 2026-07-29

## 결정

- 공개 코드는 별도 32자 이상 비밀과 canonical UUID 멱등성 키를 HMAC-SHA-256으로
  결합하고 앞 16 byte를 Base64 URL-safe no-padding으로 표현한다.
- DB에는 원문 코드가 아니라 SHA-256 lowercase hex 해시만 저장한다.
- DB에는 원문 멱등성 키도 저장하지 않고 SHA-256 lowercase hex 해시만 저장한다.
- 코드 입력은 정확한 URL-safe 형식과 길이를 검증한 뒤 해시한다.
- 대상은 `BATON`, `ROUND` enum과 안전한 target path로 나눈다.
- target path에는 scheme, authority, query, fragment, backslash, 제어문자와 `//` prefix를
  허용하지 않는다.
- base URL은 환경 설정으로만 제공하고 HTTP 또는 HTTPS origin이어야 한다.
- 리다이렉트 응답은 `no-store`와 `no-referrer`를 사용한다.
- 관리 API는 최소 32자의 환경 변수 Bearer credential로 보호한다. 이 자격은 파일럿용
  서비스 인증이며 최종 사용자 신원 모델이 아니다.
- token, access key와 전체 Authorization 값을 로그에 기록하지 않는다.
- 멱등성 키, 링크 코드 파생 비밀과 전체 short URL을 로그에 기록하지 않는다.

## 시간 경계

- `notBefore == now`이면 활성이다.
- `expiresAt == now`이면 만료다.
- `expiresAt`은 생성 시각과 `notBefore`보다 뒤여야 한다.
- 폐기는 멱등이며 최초 폐기 시각을 보존한다.

## 향후 redemption

일회성 초대·회의 입장은 공개 `GET` 리다이렉트와 분리한다. `GET`은 landing 또는
resolution만 수행하고, 인증·권한 확인 후 `POST`가 원자적으로 redemption을 소비한다.
최종 BATON session 또는 ROUND join ticket은 각 대상 서비스가 발급한다.
