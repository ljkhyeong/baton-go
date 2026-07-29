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
- 예상하지 못한 오류: `500`

## POST `/api/v1/links`

관리용 Bearer credential이 필요하다.

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

응답 `201`:

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

원문 공개 코드를 포함한 `shortUrl`은 생성 성공 응답에서만 반환한다.

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
