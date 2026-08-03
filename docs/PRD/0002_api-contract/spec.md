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
- 링크 없음: `404 LINK_NOT_FOUND`
- 저장된 target이 현재 신뢰 계약을 위반함: 존재를 숨기는 `404 LINK_NOT_FOUND`
- 아직 활성화되지 않음: `404`
- 만료·폐기: `410`
- 같은 멱등성 키를 다른 payload에 재사용: `409`
- 링크 코드 파생 설정 불일치로 기존 생성 요청을 재생할 수 없음:
  `500 LINK_CODE_REPLAY_UNAVAILABLE`
- 링크 코드 파생 키와 데이터베이스 바인딩 불일치:
  `500 LINK_CODE_CONFIGURATION_MISMATCH`
- 존재하지 않는 API 경로: `404 RESOURCE_NOT_FOUND`
- 지원하지 않는 HTTP 메서드: `405 METHOD_NOT_ALLOWED`
- 지원하지 않는 요청 본문 형식: `415 UNSUPPORTED_MEDIA_TYPE`
- 공개 resolver 처리 한도 초과: `429 RATE_LIMIT_EXCEEDED`
- 허용되지 않은 target system·purpose·locator 조합: `400 INVALID_LINK`
- target contract operations의 inventory 요청 값 오류: `400 INVALID_REQUEST`
- compliant 링크에 remediation 폐기를 요청함: `409 REMEDIATION_NOT_APPLICABLE`
- inventory 뒤 변경된 링크에 remediation 폐기를 요청함: `409 REMEDIATION_STALE`
- 예상하지 못한 오류: `500`

관리 인증 `401` 응답에는
`WWW-Authenticate: Bearer realm="baton-go-management"`를 포함하며 Bearer scheme은
대소문자를 구분하지 않고 scheme과 credential 사이에는 하나 이상의 SP를 허용한다.

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
- 요청 본문에 정의되지 않은 필드가 있으면 저장하지 않고
  `400 INVALID_REQUEST`로 거부한다.
- timeout이나 일시적인 `5xx` 뒤에는 동일한 키와 payload로 재시도할 수 있다.
- 재생 시 현재 링크 코드 파생 설정이 생성 당시와 다르면
  `500 LINK_CODE_REPLAY_UNAVAILABLE`로 실패한다. 이 오류는 같은 설정에서 반복해도
  복구되지 않으므로 자동 재시도하지 않는다. 운영자가 생성 당시 비밀을 복구하거나
  versioned key ring을 배포한 뒤 같은 키와 payload로 다시 요청한다.
- 성공과 재생 응답에는 `Cache-Control: no-store`를 포함한다.

요청:

```json
{
  "targetSystem": "BATON",
  "targetPath": "/teams/8e448211-66ae-44ab-9888-c4960648c22b/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a",
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
  "targetPath": "/teams/8e448211-66ae-44ab-9888-c4960648c22b/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a",
  "purpose": "NAVIGATION",
  "notBefore": "2026-07-29T12:00:00Z",
  "expiresAt": "2026-07-30T12:00:00Z",
  "createdAt": "2026-07-29T11:00:00Z",
  "revokedAt": null
}
```

원문 공개 코드를 포함한 `shortUrl`은 최초 생성과 동일 생성 요청 재생에서만 반환한다.
재생 시 링크의 현재 `revokedAt`을 반환한다.

### v1 신뢰 target 계약

생성 가능한 조합은 다음 두 개뿐이다.

- `BATON + NAVIGATION + /teams/{canonical-team-uuid}/seasons/{canonical-season-uuid}`
- `ROUND + MEETING_ENTRY + /room/{canonical-room-id}`

`RESOURCE_OPEN`과 표 밖 조합, 비canonical 식별자, trailing slash와 추가 segment는
`400 INVALID_LINK`로 거부하며 링크와 생성 예약을 저장하지 않는다. 정확한 정규식, 클릭
시점 권한과 rollout gate는 PRD-0003을 따른다.

## GET `/api/v1/links/{linkId}`

관리용 Bearer credential이 필요하다. 원문 공개 코드나 `shortUrl`은 반환하지 않는다.
성공 시 `200 OK`와 다음 형태의 현재 링크 상태를 반환한다.

```json
{
  "id": "00000000-0000-0000-0000-000000000000",
  "targetSystem": "BATON",
  "targetPath": "/teams/8e448211-66ae-44ab-9888-c4960648c22b/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a",
  "purpose": "NAVIGATION",
  "notBefore": "2026-07-29T12:00:00Z",
  "expiresAt": "2026-07-30T12:00:00Z",
  "createdAt": "2026-07-29T11:00:00Z",
  "revokedAt": null
}
```

## PUT `/api/v1/links/{linkId}/revocation`

관리용 Bearer credential이 필요하다. 폐기는 멱등이며 같은 링크를 다시 폐기해도 최초
`revokedAt`을 유지한 현재 상태를 `200 OK`로 반환한다. 응답 형식은 관리 조회와 같고
원문 공개 코드나 `shortUrl`은 포함하지 않는다.

저장 target이 현재 v1 계약을 위반하면 일반 관리 조회와 폐기도 raw target을 응답하지 않고
`404 LINK_NOT_FOUND`로 숨긴다. 계약 전 데이터 정리는 아래의 별도 operations 계약만 사용한다.

## Target contract v1 operations

이 API는 계약 전 저장 데이터의 일회성 inventory와 승인된 개별 폐기를 위한 관리 기능이다.
기본값은 비활성화이며 maintenance window에 public edge 차단을 검증한 뒤
`BATON_GO_TARGET_CONTRACT_OPERATIONS_ENABLED=true`와
`BATON_GO_TARGET_CONTRACT_OPERATIONS_PRIVATE_INGRESS_CONFIRMED=true`를 모두 설정해야 등록한다.
확인 flag는 네트워크 차단을 대신하지 않으며, 기존 관리 Bearer credential과 private ingress를
모두 요구한다.

모든 성공·실패 응답은 `Cache-Control: no-store`, `Referrer-Policy: no-referrer`와
`X-Request-Id`를 반환한다. 비활성 상태에서는 일반 미등록 경로와 같은
`404 RESOURCE_NOT_FOUND`다.

### GET `/api/v1/operations/link-target-contract-v1/inventory`

query parameter:

- `afterLinkId`: 선택적 UUID cursor. 해당 ID 다음 행부터 읽는다.
- `limit`: 기본 `100`, 최소 `1`, 최대 `500`이다.

DB primary key 순서의 keyset pagination으로 모든 링크를 raw 문자열 projection으로 읽고,
도메인의 v1 exact target 정책으로 분류한다. 성공 예시는 다음과 같다.

```json
{
  "contractVersion": "v1",
  "items": [
    {
      "linkId": "00000000-0000-0000-0000-000000000000",
      "compliance": "NON_COMPLIANT",
      "remediationState": "UNREVOKED",
      "creationRequestState": "PRESENT",
      "createdAt": "2026-07-29T11:00:00Z",
      "expiresAt": null,
      "revokedAt": null,
      "version": 0
    }
  ],
  "nextAfterLinkId": "00000000-0000-0000-0000-000000000000",
  "hasMore": true
}
```

- `compliance`: `COMPLIANT`, `NON_COMPLIANT`
- `remediationState`: `NOT_REQUIRED`, `UNREVOKED`, `REVOKED`
- `creationRequestState`: `PRESENT`, `MISSING`
- `hasMore=true`일 때만 `nextAfterLinkId`를 다음 cursor로 사용한다.
- 응답에는 target path, raw target system·purpose, code hash, short URL, idempotency hash와
  그 digest를 포함하지 않는다.
- SQL에 target 정규식을 복제하지 않고 application의 exact 정책으로 판정한다.

### PUT `/api/v1/operations/link-target-contract-v1/links/{linkId}/revocation`

승인한 inventory 항목 한 건만 폐기한다. 자동 bulk revoke는 제공하지 않는다.

```json
{
  "expectedVersion": 0
}
```

서버는 raw 행을 잠근 뒤 exact target 정책과 version을 다시 확인한다.

- compliant 행: `409 REMEDIATION_NOT_APPLICABLE`, 변경 없음
- inventory 뒤 변경된 non-compliant 미폐기 행: `409 REMEDIATION_STALE`, 변경 없음
- 존재하지 않는 행: `404 LINK_NOT_FOUND`
- non-compliant 행: 최초 `revokedAt`을 보존하는 멱등 폐기와 `200 OK`

```json
{
  "linkId": "00000000-0000-0000-0000-000000000000",
  "contractVersion": "v1",
  "remediationState": "REVOKED",
  "revokedAt": "2026-08-03T00:00:00Z",
  "alreadyRevoked": false
}
```

이미 폐기한 non-compliant 행의 반복 요청은 같은 `revokedAt`과
`alreadyRevoked=true`를 반환한다. target과 생성 예약 행은 수정·삭제하지 않는다. 새 링크는
원본 aggregate 소유자가 authoritative canonical target과 새 intent UUID로 정상 생성 API를
호출해 재발급한다.

## GET·HEAD `/l/{code}`

공개 endpoint다. 활성 링크이면 신뢰 대상 URL로 `302 Found`를 반환한다. `HEAD`는 `GET`과
같은 상태와 헤더를 반환하되 응답 본문이 없으며 두 메서드 모두 링크 상태를 변경하지 않는다.

응답에는 다음 헤더를 포함한다.

- `Location`
- `Cache-Control: no-store`
- `Referrer-Policy: no-referrer`
- `X-Request-Id`

이 요청은 링크 소비 횟수나 권한 상태를 변경하지 않는다.

DB에 저장된 target이 현재 PRD-0003 계약을 위반하면 존재 여부를 공개하지 않고
`404 LINK_NOT_FOUND`로 응답한다. `GET`과 `HEAD` 모두 `Location`을 포함하지 않으며
`Cache-Control: no-store`, `Referrer-Policy: no-referrer`, `X-Request-Id`를 반환한다.
`HEAD`에는 본문이 없다. 내부 metric과 안전한 로그로 운영 경보를 남기되 공개 코드,
target path와 전체 short URL은 기록하지 않는다.

### 공개 resolver 과부하 backstop

`GET·HEAD /l/{code}`는 데이터베이스 조회 전에 인스턴스 단위 aggregate rate limit을
적용한다. 존재 여부와 코드 형식에 관계없이 이 경로의 모든 `GET`과 `HEAD`가 하나의
고정 용량 버킷을 공유한다. 클라이언트 IP나 `X-Forwarded-For`는 식별자 또는 신뢰
경계로 사용하지 않는다.

용량과 window는 배포 환경이 명시적으로 설정하는 운영 안전값이며 사용자별 제품 quota가
아니다. 한도를 초과하면 `429 RATE_LIMIT_EXCEEDED`와 다음 헤더를 반환한다.

- `Retry-After`: 현재 window가 갱신될 때까지의 초 단위 대기 시간
- `Cache-Control: no-store`
- `X-Request-Id`

`HEAD`의 `429`도 같은 상태와 헤더를 반환하지만 응답 본문은 없다. 이 backstop은
인스턴스 메모리만 사용하므로 여러 replica의 합산 요청량을 제한하지 않는다. 운영 공개
경로에는 별도의 edge 또는 distributed rate limiting과 access-log 코드 마스킹을 반드시
적용한다.
