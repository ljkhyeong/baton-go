# PRD-0002: BATON GO API 계약

- 상태: 초기 기준선
- 기본 경로: `/api/v1`

## 공통 응답

이 문서에 정의한 API의 모든 성공·오류 응답은 `Cache-Control: no-store`,
`Referrer-Policy: no-referrer`와 `X-Request-Id`를 포함한다.

클라이언트가 `^[A-Za-z0-9._-]{1,64}$` 형식의 `X-Request-Id`를 보내면 서버는 같은 값을
응답한다. 헤더가 없거나 형식이 다르면 서버가 새 UUID를 발급한다. 오류 응답 본문의
`requestId`는 응답 헤더와 같은 값이다. 공개 링크의 HTML 안내 화면에는 같은 값을
문의용 요청 번호로 표시한다.

관리 링크 API와 대상 계약 운영 API의 성공 응답은 `application/json`이다. `Accept`가 없거나
`*/*`이면 기존처럼 JSON을 반환한다. HTML·XML 등 JSON과 호환되지 않는 형식만 요청하면
인증·권한 검사 뒤 Spring의 요청 매핑 단계에서 `406 INVALID_REQUEST`로 거부한다.
이때 생성·폐기 서비스는 호출하지 않고 관리 작업 완료 이력도 남기지 않는다.

## 공통 오류

기본 오류 응답은 다음 JSON 형식이다. 공개 링크의 브라우저 안내 화면은 아래
`GET·HEAD /l/{code}`의 응답 형식 규칙을 따른다.
HTML 안내가 선택된 경우를 제외한 공통 오류는 `Content-Type: application/json`으로 반환한다.
XML 등 지원하지 않는 응답 형식만 요청해도 원래 오류 상태·코드를 JSON으로 반환하며,
응답 변환 실패 때문에 다른 오류 상태나 서블릿 기본 오류 화면으로 바꾸지 않는다.

```json
{
  "code": "UPPER_SNAKE_CASE",
  "message": "사용자가 취할 행동을 설명하는 메시지",
  "requestId": "request-id"
}
```

- 입력 형식 오류: `400 INVALID_REQUEST`
- 멱등성 키 누락·형식 오류: `400 INVALID_IDEMPOTENCY_KEY`
- 생성 시각이 Java/JDBC의 UTC 지원 저장 범위나 정밀도를 벗어남: `400 INVALID_REQUEST`
- 관리 인증 누락·토큰 검증 실패: `401 MANAGEMENT_AUTHENTICATION_REQUIRED`
- 관리 JWT에 요청한 작업의 scope가 없음: `403 MANAGEMENT_AUTHORIZATION_REQUIRED`
- JWK 조회 등 관리 인증 서비스 장애: `500 INTERNAL_ERROR`
- 링크 없음: `404 LINK_NOT_FOUND`
- 저장된 대상이 현재 신뢰 계약을 위반함: 존재를 숨기는 `404 LINK_NOT_FOUND`
- 아직 활성화되지 않음: `404 LINK_NOT_ACTIVE`
- 만료: `410 LINK_EXPIRED`
- 폐기: `410 LINK_REVOKED`
- 보존 기간 정리 후 같은 생성 요청 재시도: `410 LINK_PURGED`
- 같은 멱등성 키를 다른 요청 내용에 재사용: `409 IDEMPOTENCY_KEY_REUSED`
- 기존 멱등 예약이 가리키는 링크를 복구할 수 없음:
  `500 LINK_CREATION_REPLAY_UNAVAILABLE`
- 링크 코드 파생 설정 불일치로 기존 URL을 복원할 수 없음:
  `500 LINK_CODE_REPLAY_UNAVAILABLE`
- 최초 생성에 사용한 정규 공개 출처를 기존 예약에서 복구할 수 없음:
  `500 PUBLIC_LINK_ORIGIN_REPLAY_UNAVAILABLE`
- 링크 코드 파생 키와 데이터베이스 바인딩 불일치:
  `500 LINK_CODE_CONFIGURATION_MISMATCH`
- 존재하지 않는 API 경로: `404 RESOURCE_NOT_FOUND`
- 지원하지 않는 HTTP 메서드: `405 METHOD_NOT_ALLOWED`
- 관리 API에서 지원하지 않는 응답 형식만 요청함: `406 INVALID_REQUEST`
- 지원하지 않는 요청 본문 형식: `415 UNSUPPORTED_MEDIA_TYPE`
- 단축 링크 요청 한도 초과: `429 RATE_LIMIT_EXCEEDED`
- 공용 요청 제한 저장소 장애: `503 RATE_LIMIT_UNAVAILABLE`
- 허용되지 않은 대상 시스템·목적·위치 식별자 조합: `400 INVALID_LINK`
- 대상 계약 운영 기능의 목록 조사 요청 값 오류: `400 INVALID_REQUEST`
- 대상 계약 정리 API로 규칙을 충족하는 링크의 폐기를 요청함: `409 REMEDIATION_NOT_APPLICABLE`
- 대상 계약 정리 API로 목록 조사 뒤 변경된 링크의 폐기를 요청함: `409 REMEDIATION_STALE`
- 예상하지 못한 오류: `500 INTERNAL_ERROR`

관리 인증 `401` 응답에는
`WWW-Authenticate: Bearer realm="baton-go-management"`를 포함한다. 관리 호출자는
`iss`가 설정한 발급자, `aud`가 `baton-go`, 유효 시간이 현재 범위이며 요청 작업의 scope를
포함한 서명 JWT를 표준 `Authorization: Bearer <jwt>` 형식으로 보낸다. JWT 서명·시간·발급자·
대상 검증과 scope 권한 변환은 Spring Security OAuth2 Resource Server가 수행한다.
만료 시각 `exp`는 필수이며 누락하거나 이미 만료된 JWT는 같은 `401`로 거부한다.
`nbf`는 선택 사항으로 유지하고, 시각 비교에는 Spring의 기본 시계 오차 허용 범위를 적용한다.
JWK 조회 등 인증 서비스 장애는 토큰 오류와 구분해 `500 INTERNAL_ERROR`로 응답하고 관리
작업을 실행하지 않는다. 공통 오류 본문과 `requestId`를 유지하며 `WWW-Authenticate`는
포함하지 않는다.
세미콜론이나 비정규 인코딩을 포함한 관리 경로는 인증 처리 전에 Spring Security HTTP 방화벽이
`400`으로 거부할 수 있으며 이를 정규 경로로 보정하지 않는다.

관리 쓰기 완료 이력은 JWT의 `sub`를 서비스 식별자로 사용한다. 기존 HTTP 인증 조건과
응답 형식은 바꾸지 않는다. 기록 대상, 민감정보 제외와 보존 정책은
[관리 작업 이력 운영 절차](../../RUNBOOK/management-operation-history.md)를 따른다.

## POST `/api/v1/links`

`baton-go.links.create` scope가 있는 관리 JWT가 필요하다.

필수 헤더:

```http
Idempotency-Key: 8e448211-66ae-44ab-9888-c4960648c22b
```

- 값은 다음 정규식과 정확히 일치하는 소문자 정규 UUID다. 버전은 `1..5`, RFC
  변형의 첫 문자는 `8`, `9`, `a`, `b` 중 하나이며 대문자, nil UUID, 버전 `0`과
  RFC 비준수 변형을 정규화해서 받아들이지 않는다.

```text
^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$
```

- 호출자는 하나의 링크 생성 요청에 같은 키를 사용하고 원본 도메인 상태와 함께 영속화한다.
- 성공 응답의 `Location`은 `/api/v1/links/{id}`다.
- 최초 성공은 `201 Created`와 `Idempotency-Replayed: false`를 반환한다.
- 같은 키와 같은 요청 내용의 재시도는 동일한 `id`, `shortUrl`, `Location`을
  `200 OK`와 `Idempotency-Replayed: true`로 반환한다.
- 최초 생성의 정규 공개 출처는 멱등 예약에 함께 저장한다. 이후 현재
  `BATON_GO_PUBLIC_BASE_URL` 설정이 바뀌어도 재시도는 저장된 출처와 같은 공개 코드로
  최초 `shortUrl`을 그대로 반환한다. 저장된 출처가 없거나 정규 형식이 아니면 현재
  설정으로 추정하지 않고 `500 PUBLIC_LINK_ORIGIN_REPLAY_UNAVAILABLE`로 실패한다.
- 같은 키와 다른 요청 내용은 `409 IDEMPOTENCY_KEY_REUSED`로 거부한다.
- 자동 삭제 표시가 있는 예약은 같은 요청 내용이면 `410 LINK_PURGED`, 다른 내용이면
  `409 IDEMPOTENCY_KEY_REUSED`로 거부하며 새 링크를 만들지 않는다. 정리 뒤 단축 링크 접속 처리와 관리
  조회·폐기는 `404 LINK_NOT_FOUND`이고 목록에서 제외한다. [보존 정책](../../ADR/0012_link-retention/adr.md)을 따른다.
- 자동 삭제 표시 없이 기존 멱등 예약은 있으나 예약이 가리키는 링크가 없으면 존재하지 않는 링크로 숨기거나 새 링크를
  만들지 않고 `500 LINK_CREATION_REPLAY_UNAVAILABLE`로 실패한다. 이 오류는 저장 일관성 복구가
  끝날 때까지 자동 재시도하지 않는다.
- 키 누락·형식 오류는 `400 INVALID_IDEMPOTENCY_KEY`로 거부한다.
- 과거 배포가 이미 저장한 링크 생성 요청에 한해서는 당시 파서가 허용했던 대문자, nil,
  버전 `0`·`6..f`, RFC 비준수 변형의 정규 UUID를 소문자로 정규화해 조회한다.
  동일한 멱등성 키 해시의 기존 예약과 동일 요청 내용이 모두 확인될 때만 `200` 재생하며,
  예약이 없으면 `400 INVALID_IDEMPOTENCY_KEY`로 거부하고 새 예약이나 링크를 만들지 않는다.
  이 재생 전용 예외는 신규 생성 문법을 확장하지 않는다.
- 요청 본문에 정의되지 않은 필드가 있으면 저장하지 않고
  `400 INVALID_REQUEST`로 거부한다.
- `targetSystem`과 `purpose`는 계약에 정의된 대문자 열거형 이름의 JSON 문자열만 허용한다.
  숫자 토큰, 숫자 문자열, 앞뒤 공백과 Jackson 순번·공백 제거 강제 변환은 링크나 생성 예약을
  저장하지 않고 `400 INVALID_REQUEST`로 거부한다.
- 시간 초과나 일시적인 `5xx` 뒤에는 동일한 키와 요청 내용으로 재시도할 수 있다.
- 생성 예약에는 발급에 사용한 키 ID를 저장한다. 기존 예약은 `legacy`에 대응하며 현재 발급
  키를 교체해도 재생에는 예약에 저장된 키를 사용한다. 키 ID와 지문은 응답에 포함하지 않는다.
- 재생 시 저장된 키를 사용할 수 없거나 해당 키의 코드 파생 결과가 저장 코드 해시와 다르면
  `500 LINK_CODE_REPLAY_UNAVAILABLE`로 실패한다. 이 오류는 같은 설정에서 반복해도
  복구되지 않으므로 자동 재시도하지 않는다. 운영자가 생성 당시 비밀을 복구하거나
  버전별 키 묶음을 배포한 뒤 같은 키와 요청 내용으로 다시 요청한다.

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

- 선택적인 `notBefore`와 `expiresAt`은 UTC `Z` 접미사를 사용하는 JSON 문자열이어야 한다.
  숫자 타임스탬프, 오프셋·공백 표현, 윤초, `24:00`과 파서가 다른 시각으로 보정하는
  표현은 링크나 생성 예약을 저장하기 전에 `400 INVALID_REQUEST`로 거부한다.
- 두 시각은 마이크로초 단위로 정확히 표현 가능하고 Java/JDBC가
  역산 그레고리력 `Instant`를 MySQL `DATETIME(6)` 원문 값과 동일하게 보존하는 지원
  범위인 `1582-10-15T00:00:00Z` 이상
  `9999-12-31T23:59:59.999999Z` 이하여야 한다. 범위 밖이거나 소수 초 7번째부터 9번째
  자리 중 하나라도 0이 아니면 서버가 반올림하거나 절삭하지 않고 링크 생성 예약 전에
  `400 INVALID_REQUEST`로 거부한다.
- 과거 배포가 범위 안의 나노초 입력을 마이크로초로 절삭해 이미 저장한 의도는 예외다.
  동일한 멱등성 키 해시의 기존 예약이 있고, 같은 마이크로초 절삭 결과가 저장 요청 내용과
  일치할 때만 `200` 재생한다. 예약이 없으면 `400 INVALID_REQUEST`로 거부하며 새 행을
  만들지 않는다. 지원 저장 범위 밖 시각은 기존 예약 여부와 관계없이 항상 거부한다.
- 호출자는 재시도할 때 두 시각을 포함한 동일한 정규 요청 내용을 사용한다.

최초 생성 응답 `201`, 동일 요청 재시도 응답 `200`:

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

원문 공개 코드를 포함한 `shortUrl`은 최초 생성과 동일 생성 요청의 재시도에서만 반환한다.
재시도 응답에는 링크의 현재 `revokedAt`을 반환한다.

### v1 신뢰 대상 계약

생성 가능한 조합은 다음 두 개뿐이다.

- `BATON + NAVIGATION + /teams/{canonical-team-uuid}/seasons/{canonical-season-uuid}`
- `ROUND + MEETING_ENTRY + /room/{canonical-room-id}`

`RESOURCE_OPEN`과 표 밖 조합, 비정규 식별자, 끝 슬래시와 추가 경로 구간은
`400 INVALID_LINK`로 거부하며 링크와 생성 예약을 저장하지 않는다. 정확한 정규식, 클릭
시점 권한과 배포 전 점검은 PRD-0003을 따른다.

## GET `/api/v1/links`

`baton-go.links.read` scope가 있는 관리 JWT가 필요하다. 일반 운영 중 링크 ID를 찾거나
여러 링크 상태를 확인하는 읽기 전용 목록이다. `HEAD`도 같은 읽기 권한과 조회 조건을
사용하며 본문 없이 응답한다. 기존 대상 계약 운영 API의 활성화 설정은 필요하지 않다.

쿼리 매개변수는 모두 선택 사항이며 지정한 조건을 함께 적용한다.

| 매개변수 | 의미 |
| --- | --- |
| `afterLinkId` | 이전 응답의 `nextAfterLinkId`. 생략하면 기본 키 순서의 첫 행부터 검사한다. |
| `limit` | 한 요청에서 필터를 적용할 저장 행의 최대 수. 기본 `100`, 범위 `1..500`이다. |
| `targetSystem` | `BATON` 또는 `ROUND` 대상만 반환한다. |
| `createdFrom` | 이 시각 이상에 생성된 링크만 반환한다. 시작 시각을 포함한다. |
| `createdBefore` | 이 시각 전에 생성된 링크만 반환한다. 끝 시각은 포함하지 않는다. |
| `status` | 아래 관리 조회와 같은 `ACTIVE`, `NOT_ACTIVE`, `EXPIRED`, `REVOKED` 중 하나다. |

생성 기간은 Spring의 표준 ISO 날짜·시각 변환을 사용하며 `Z` 또는 명시적 UTC 오프셋이
있는 절대 시각을 받는다. 예를 들어 `2026-07-29T10:00:00Z`와
`2026-07-29T19:00:00+09:00`는 같은 시각이다. URL에서 `+`는 `%2B`로 인코딩한다.
두 시각을 모두 지정하면 `createdBefore > createdFrom`이어야 한다. 형식이 잘못되었거나
검사 한도·기간 조건을 위반하면 `400 INVALID_REQUEST`다.

성공 시 `200 OK`와 다음 필드를 반환한다. `items`의 각 항목은 아래 단건 관리 조회와
같은 형식이며 원문 공개 코드, 코드 해시, 멱등성 키·해시와 `shortUrl`은 없다.

```json
{
  "items": [],
  "nextAfterLinkId": "00000000-0000-4000-8000-000000000002",
  "hasMore": true,
  "evaluatedAt": "2026-07-29T12:00:00Z"
}
```

- 저장 대상은 기존 v1 정책으로 검사하며 비허용 조합·경로·알 수 없는 열거형 행은 목록에서
  제외한다. 해당 행의 조사·폐기는 기존 대상 계약 운영 API를 사용한다.
- 한 요청은 다음 행 존재 확인용 한 건을 포함해 최대 `limit + 1`건만 읽는다. 필터를
  통과한 항목만 반환하므로 `items`가 `limit`보다 적거나 비어 있어도 `hasMore=true`일 수 있다.
  `hasMore`는 아직 검사하지 않은 저장 행의 존재이며 조건에 맞는 결과가 남았다는 보장은 아니다.
- `hasMore=true`일 때는 마지막으로 검사한 행의 ID가 `nextAfterLinkId`다. 마지막 반환 항목의
  ID로 추정하지 않고 이 커서와 같은 필터로 다음 페이지를 요청한다. 끝에 도달하면
  `hasMore=false`, `nextAfterLinkId=null`이다. 전체 결과 건수는 계산하지 않는다.
- DB 조회 후 얻은 하나의 `evaluatedAt`으로 모든 항목의 상태를 판정한다. 각 항목의
  `evaluatedAt`도 최상위 값과 같다. 페이지 사이에 발생한 생성·폐기·시간 경과를 고정하는
  전체 목록 스냅샷은 제공하지 않으며, 커서 앞에 추가된 행은 새 검색에서 확인한다.
- 대상 정규식·상태 판정을 SQL에 복제하지 않고 기존 도메인 정책을 재사용한다. 이 API는
  한정된 검사량으로 운영 조회를 지원하며 대량 데이터의 즉시 검색이나 분석 용도가 아니다.
  BATON 일반 목록 화면에서 링크마다 동기 호출하는 방식으로 사용하지 않는다.

## GET `/api/v1/links/{linkId}`

`baton-go.links.read` scope가 있는 관리 JWT가 필요하다. 원문 공개 코드나 `shortUrl`은
반환하지 않는다.
성공 시 `200 OK`와 다음 형태의 현재 링크 상태를 반환한다.
같은 경로의 `HEAD`도 `baton-go.links.read` scope를 요구하며, 같은 상태 판정 뒤 본문 없이
응답한다.

`status`는 DB 조회 후 서버의 `Clock`으로 얻은 `evaluatedAt` 시점의 GO 링크 이용 상태다.
단축 링크 접속 처리와 같은 도메인 정책을 사용하며 다음 순서로 판정한다.

| `status` | 판정 |
| --- | --- |
| `REVOKED` | `revokedAt`이 있음. 활성·만료 조건보다 우선 |
| `NOT_ACTIVE` | `notBefore`가 있고 판정 시각이 그 시각 전 |
| `EXPIRED` | `expiresAt`이 있고 판정 시각이 그 시각 이상 |
| `ACTIVE` | 위 조건에 해당하지 않음 |

신뢰 대상 계약에 맞는 링크라면 만료·폐기 상태여도 관리 조회는 `200`이다. `status`는
판정 시점에 읽은 상태이며 응답 이후의 폐기·만료, 대상 서비스의 가용성이나 접근 권한을
보장하지 않는다. 호출자는 관리·진단 화면에서 이 값을 사용하되 BATON의 일반 목록 조회에
링크별 GO 동기 호출을 추가하지 않는다. 생성·재생 응답에는 두 필드를 추가하지 않는다.

```json
{
  "id": "00000000-0000-0000-0000-000000000000",
  "targetSystem": "BATON",
  "targetPath": "/teams/8e448211-66ae-44ab-9888-c4960648c22b/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a",
  "purpose": "NAVIGATION",
  "notBefore": "2026-07-29T12:00:00Z",
  "expiresAt": "2026-07-30T12:00:00Z",
  "createdAt": "2026-07-29T11:00:00Z",
  "revokedAt": null,
  "status": "ACTIVE",
  "evaluatedAt": "2026-07-29T12:00:00Z"
}
```

## PUT `/api/v1/links/{linkId}/revocation`

`baton-go.links.revoke` scope가 있는 관리 JWT가 필요하다. 폐기는 멱등이며 같은 링크를 다시
폐기해도 최초 `revokedAt`을 유지한 현재 상태를 `200 OK`로 반환한다. 응답 형식은 관리 조회와
같고 원문 공개 코드나 `shortUrl`은 포함하지 않는다.

`status`는 `REVOKED`이며 `evaluatedAt`은 각 요청의 처리 시각이다. 반복 폐기의 판정 시각은
달라질 수 있지만 최초 `revokedAt`과 저장 데이터는 바꾸지 않는다.

저장 대상이 현재 v1 계약을 위반하면 일반 관리 조회와 폐기도 원문 대상을 응답하지 않고
`404 LINK_NOT_FOUND`로 숨긴다. 계약 전 데이터 정리는 아래의 별도 운영 기능 계약만 사용한다.

## 대상 계약 v1 운영 기능

이 API는 계약 전 저장 데이터의 일회성 목록 조사와 승인된 개별 폐기를 위한 관리 기능이다.
기본값은 비활성화이며 유지보수 시간대에 공개 경계 차단을 검증한 뒤
`BATON_GO_TARGET_CONTRACT_OPERATIONS_ENABLED=true`와
`BATON_GO_TARGET_CONTRACT_OPERATIONS_PRIVATE_INGRESS_CONFIRMED=true`를 모두 설정해야 등록한다.
확인 값은 네트워크 차단을 대신하지 않으며,
`baton-go.target-contract.operate` scope가 있는 관리 JWT와 비공개 Ingress를
모두 요구한다.

관리 인증은 운영 컨트롤러의 등록 여부보다 먼저 적용한다.
따라서 비활성 상태에서 관리 인증이 없거나 올바르지 않은 요청은
`401 MANAGEMENT_AUTHENTICATION_REQUIRED`다. 유효한 관리 인증을 통과한 요청은 일반
미등록 경로와 같은 `404 RESOURCE_NOT_FOUND`다.

### GET `/api/v1/operations/link-target-contract-v1/inventory`

쿼리 매개변수:

- `afterLinkId`: 선택적 UUID 커서. 해당 ID 다음 행부터 읽는다.
- `limit`: 기본 `100`, 최소 `1`, 최대 `500`이다.

링크 ID 기준 커서로 모든 링크를 나눠 조회한다. DB에 저장된 원문 문자열을 읽어
도메인의 v1 허용 대상 규칙으로 분류한다. 성공 예시는 다음과 같다.

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
- `hasMore=true`일 때만 `nextAfterLinkId`를 다음 커서로 사용한다.
- 응답에는 대상 경로, 원문 대상 시스템·목적, 코드 해시, 단축 URL, 멱등성 해시와
  그 요약값을 포함하지 않는다.
- SQL에 대상 정규식을 복제하지 않고 애플리케이션의 정확한 정책으로 판정한다.

### PUT `/api/v1/operations/link-target-contract-v1/links/{linkId}/revocation`

승인한 목록 조사 항목 한 건만 폐기한다. 자동 일괄 폐기는 제공하지 않는다.

```json
{
  "expectedVersion": 0
}
```

`expectedVersion`은 `0..9223372036854775806` 범위의 JSON 정수 토큰만 허용한다. 문자열,
소수와 지수 표기처럼 Jackson이 정수로 강제 변환할 수 있는 다른 표현과 다음 버전으로
증가할 수 없는 `9223372036854775807`은 행을 잠그거나 폐기하지 않고
`400 INVALID_REQUEST`로 거부한다.

서버는 원문 행을 잠근 뒤 정확한 대상 정책과 버전을 다시 확인한다.

- 준수 행: `409 REMEDIATION_NOT_APPLICABLE`, 변경 없음
- 목록 조사 뒤 변경된 비준수 미폐기 행: `409 REMEDIATION_STALE`, 변경 없음
- 존재하지 않는 행: `404 LINK_NOT_FOUND`
- 비준수 행: 최초 `revokedAt`을 보존하는 멱등 폐기와 `200 OK`

```json
{
  "linkId": "00000000-0000-0000-0000-000000000000",
  "contractVersion": "v1",
  "remediationState": "REVOKED",
  "revokedAt": "2026-08-03T00:00:00Z",
  "alreadyRevoked": false
}
```

이미 폐기한 비준수 행의 반복 요청은 같은 `revokedAt`과
`alreadyRevoked=true`를 반환한다. 대상과 생성 예약 행은 수정·삭제하지 않는다.
원본을 관리하는 BATON·ROUND 서비스가 대상 경로를 확인한 뒤 새 요청 UUID로
링크 생성 API를 호출해 재발급한다.

## GET·HEAD `/l/{code}`

공개 엔드포인트다. 활성 링크이면 신뢰 대상 URL로 `302 Found`를 반환한다. `HEAD`는 `GET`과
같은 상태와 헤더를 반환하되 응답 본문이 없으며 두 메서드 모두 링크 상태를 변경하지 않는다.
오류 응답의 `HEAD`도 선택한 JSON·HTML의 `Content-Type`을 유지한다. 본문 전송 제외는
Spring·서블릿의 표준 HEAD 처리에 맡긴다.

성공 응답은 공통 헤더 외에 신뢰 대상 URL을 담은 `Location`을 포함한다.

이 요청은 링크 소비 횟수나 권한 상태를 변경하지 않는다.

DB에 저장된 대상이 현재 PRD-0003 계약을 위반하면 존재 여부를 공개하지 않고
`404 LINK_NOT_FOUND`로 응답한다. `GET`과 `HEAD` 모두 `Location`을 포함하지 않으며
`HEAD`에는 본문이 없다. 내부 지표와 안전한 로그로 운영 경보를 남기되 공개 코드,
대상 경로와 전체 단축 URL은 기록하지 않는다.

### 브라우저 오류 안내

- `Accept` 헤더에서 HTML을 우선하는 브라우저에는 미존재·미활성·만료·폐기와 GO가 처리한
  요청 제한·서버 오류를 한글 HTML로 안내한다. Spring의 응답 형식 선택을 사용하며 헤더가
  없거나 `*/*`만 보내면 기존 JSON을 반환한다. `application/json`을 우선하는 클라이언트도
  기존 오류 형식을 유지한다.
- 상태 코드는 그대로 `404`·`410`·`429`·`500`·`503`이며 오류 화면을 위해 `200`으로 바꾸거나 다른 URL로
  리다이렉트하지 않는다. `HEAD`는 같은 상태와 헤더를 반환하고 본문은 비운다.
- 미활성 링크는 보낸 사람에게 이용 시간을 확인하도록, 만료·폐기 링크는 새 링크를
  요청하도록 안내한다. 링크가 없을 때는 주소 확인 또는 새 링크 요청을 안내한다.
- `429`는 `Retry-After`와 같은 초 단위 대기 시간을 화면에 표시한다. `500`은 잠시 후
  다시 열도록 안내하며, 두 화면 모두 계속 실패할 때 전달할 요청 번호를 표시한다.
  자동 새로고침이나 자동 재시도를 넣지 않으며 서버 오류의 내부 예외 원문을 표시하지 않는다.
- `503 RATE_LIMIT_UNAVAILABLE` 화면의 제목은 "지금은 링크를 열 수 없습니다."이며 본문에서
  재시도를 안내한다. JSON은 요청 처리 한도 확인 실패를 설명하는 기존 메시지를 유지한다.
- 저장 대상 계약 위반은 일반 미존재와 같은 화면을 반환하고 기존 내부 지표·로그만 남긴다.
  화면에는 공개 코드, 내부 링크 ID, 대상 경로와 단축 URL을 넣지 않는다.
- HTML은 공통 보안 헤더와 요청 번호를 유지하고 외부 자산·스크립트·쿠키를 사용하지 않는다.
  HTML 응답의 CSP는 스크립트·외부 자산·폼 전송·프레임 삽입을 차단하고 정적 인라인 스타일만
  허용한다.
- 관리 API의 인증·권한·서버 오류와 이 목록 밖 오류는 기존 JSON 계약을 유지한다.
  GO에 도달하기 전에 Ingress가 반환한 오류나 프로세스 중단은 이 화면의 처리 범위가 아니다.

컨트롤러 오류와 요청 제한은 `@ExceptionHandler(produces=...)`로 응답 형식을 선택한다.
요청 제한은 `/l/{code}`에 등록한 MVC `HandlerInterceptor`가 컨트롤러 실행과 DB 조회 전에
판정한다. `Accept` 파서나 응답 변환을 별도로 구현하지 않고 Spring의 콘텐츠 협상과 메시지
변환을 사용하며, HTML 템플릿과 보안 헤더는 한곳에서 재사용한다.

### 단축 링크 요청 제한

`GET·HEAD /l/{code}`는 데이터베이스 조회 전에 인스턴스 단위 통합 요청률 제한을
적용한다. 존재 여부와 코드 형식에 관계없이 이 경로의 모든 `GET`과 `HEAD`가 하나의
고정 용량 버킷을 공유한다. 클라이언트 IP나 `X-Forwarded-For`는 식별자 또는 신뢰
경계로 사용하지 않는다.

용량과 시간 구간은 배포 환경이 명시적으로 설정하는 운영 안전값이며 사용자별 제품 할당량이
아니다. 한도를 초과하면 `429 RATE_LIMIT_EXCEEDED`와 현재 시간 구간이 갱신될 때까지의
초 단위 대기 시간을 담은 `Retry-After`를 반환한다.

`HEAD`의 `429`도 같은 상태와 헤더를 반환하지만 응답 본문은 없다. 이 안전장치는
인스턴스 메모리만 사용하므로 여러 복제본의 합산 요청량을 제한하지 않는다. 운영 공개
경로에는 별도의 경계 또는 분산 요청률 제한과 접근 로그 코드 마스킹을 반드시
적용한다.

## 공용 요청 제한

분산 제한을 활성화하면 공개 `GET·HEAD /l/{code}`의 허용량을 모든 Pod가 공유한다.
한도 초과는 기존 `429 RATE_LIMIT_EXCEEDED`와 `Retry-After`이며, Redis 오류는
`503 RATE_LIMIT_UNAVAILABLE`다. HTML·JSON·HEAD는 기존 공개 오류 표현을 따르고 링크
조회는 실행하지 않는다. 관리 API는 이 제한에서 제외한다. [운영 절차](../../RUNBOOK/distributed-public-rate-limit.md)를 따른다.
