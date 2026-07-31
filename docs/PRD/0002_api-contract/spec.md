# PRD-0002: BATON GO API 계약

- 상태: 초기 기준선
- base path: `/api/v1`

## 공통 오류

```json
{
  "code": "UPPER_SNAKE_CASE",
  "message": "사용자가 취할 행동을 설명하는 메시지",
  "requestId": "optional-request-id"
}
```

- 입력 형식 오류: `400`
- 관리 인증 누락·실패: `401`
- 링크 없음: `404`
- 아직 활성화되지 않음: `404`
- 만료·폐기: `410`
- 같은 멱등성 키를 다른 payload에 재사용: `409`
- 존재하지 않는 API 경로: `404 RESOURCE_NOT_FOUND`
- 지원하지 않는 HTTP 메서드: `405 METHOD_NOT_ALLOWED`
- 지원하지 않는 요청 본문 형식: `415 UNSUPPORTED_MEDIA_TYPE`
- 예상하지 못한 오류: `500`

## POST `/api/v1/links`

관리용 Bearer credential이 필요하다.

필수 헤더:

```http
Idempotency-Key: 8e448211-66ae-44ab-9888-c4960648c22b
```

- 값은 canonical UUID 형식이다.
- 호출자는 하나의 생성 intent에 같은 키를 사용하고 원본 도메인 상태와 함께 영속화한다.
- 최초 성공은 `201 Created`와 `Idempotency-Replayed: false`를 반환한다.
- 같은 키와 같은 payload의 재시도는 동일한 `id`, `shortUrl`, `Location`을
  `200 OK`와 `Idempotency-Replayed: true`로 반환한다.
- 같은 키와 다른 payload는 `409 IDEMPOTENCY_KEY_REUSED`로 거부한다.
- 키 누락·형식 오류는 `400 INVALID_IDEMPOTENCY_KEY`로 거부한다.

요청:

```json
{
  "targetSystem": "BATON",
  "targetPath": "/teams/00000000-0000-0000-0000-000000000000",
  "purpose": "NAVIGATION",
  "notBefore": "2026-07-29T12:00:00Z",
  "expiresAt": "2026-07-30T12:00:00Z"
}
```

최초 응답 `201`, 재생 응답 `200`:

```json
{
  "id": "00000000-0000-0000-0000-000000000000",
  "shortUrl": "https://go.example/l/opaque-code",
  "targetSystem": "BATON",
  "targetPath": "/teams/00000000-0000-0000-0000-000000000000",
  "purpose": "NAVIGATION",
  "notBefore": "2026-07-29T12:00:00Z",
  "expiresAt": "2026-07-30T12:00:00Z",
  "createdAt": "2026-07-29T11:00:00Z",
  "revokedAt": null
}
```

원문 공개 코드를 포함한 `shortUrl`은 최초 생성과 동일 생성 요청 재생에서만 반환한다.
재생 시 링크의 현재 `revokedAt`을 반환한다.

## GET `/api/v1/links/{linkId}`

관리용 Bearer credential이 필요하다. 원문 공개 코드나 `shortUrl`은 반환하지 않는다.

## PUT `/api/v1/links/{linkId}/revocation`

관리용 Bearer credential이 필요하다. 폐기는 멱등이며 같은 링크를 다시 폐기해도 최초
`revokedAt`을 유지한 현재 상태를 반환한다.

## GET `/l/{code}`

공개 endpoint다. 활성 링크이면 신뢰 대상 URL로 `302 Found`를 반환한다.

응답에는 다음 헤더를 포함한다.

- `Location`
- `Cache-Control: no-store`
- `Referrer-Policy: no-referrer`
- `X-Request-Id`

이 요청은 링크 소비 횟수나 권한 상태를 변경하지 않는다.
