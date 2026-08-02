# PRD-0003: BATON·ROUND 교차 서비스 링크 계약

- 상태: 확정
- 계약 버전: v1
- 확정일: 2026-08-02
- 적용 서비스: BATON GO, BATON, ROUND

## 1. 목적과 효력

이 문서는 BATON GO의 공개 링크가 BATON과 ROUND의 어느 경로로 이동할 수 있는지, 이동
뒤 최종 권한을 어느 서비스가 어떻게 판단하는지, 원격 생성과 폐기를 누가 책임지는지를
고정한다.

- 제품 원칙과 링크 수명주기는 PRD-0001을 따른다.
- GO의 HTTP 상태·헤더·오류 형식은 PRD-0002를 따른다.
- 대상 조합, locator 문법과 클릭 시점 권한 경계는 이 문서를 우선한다.
- 이 계약의 확정은 통합 구현 완료나 운영 배포 승인을 뜻하지 않는다. 9절의 gate를 모두
  통과하기 전에는 해당 흐름을 공개하지 않는다.

## 2. 용어와 소유권

`targetPath`는 URL 전체가 아니라 **설정된 신뢰 origin 기준의 origin-relative absolute
path**다. GO는 `targetSystem`으로 origin을 선택하고 canonical `targetPath`를 그대로 붙인다.

| 주체 | 소유하는 계약 | 소유하지 않는 계약 |
| --- | --- | --- |
| BATON GO | 공개 코드, 활성·만료·폐기, 허용 target 조합과 locator 검증, 신뢰 origin 리다이렉트 | BATON workspace 권한, ROUND room 입장, 사용자 세션과 참여권 |
| BATON | team·season·resource와 room mapping, room tombstone, workspace access key, 향후 계정 세션·멤버십, ROUND 참여권·JWK 발급 | GO 공개 코드와 링크 수명주기, ROUND signaling admission |
| ROUND | canonical room ID, pre-join, 참여권 검증, peer·signaling·TURN admission | BATON 사용자·멤버십, GO 링크 상태 |
| 생성 호출자 | 원본 aggregate, 생성 intent UUID, 재시도·보상·폐기 요청 | GO 내부 링크 코드와 대상 서비스의 최종 권한 |

locator의 식별자는 위치를 찾기 위한 값일 뿐 권한 증명이 아니다. BATON access key, 관리
Bearer credential, 사용자 session identifier, CSRF token, ROUND participation grant와 TURN
credential은 GO의 요청·DB·대상 경로·로그에 들어가면 안 된다.

## 3. v1 허용 조합과 canonical locator

다음 두 행만 v1에서 유효하다.

| `targetSystem` | `purpose` | canonical `targetPath` | 의미 |
| --- | --- | --- | --- |
| `BATON` | `NAVIGATION` | `/teams/{teamId}/seasons/{seasonId}` | 이미 workspace 권한을 보유한 브라우저의 복귀 |
| `ROUND` | `MEETING_ENTRY` | `/room/{roomId}` | room pre-join과 admission 절차를 시작하는 landing |

정확한 wire 형식은 다음과 같다.

```text
BATON
^/teams/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/seasons/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$

ROUND
^/room/[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}$
```

- `teamId`와 `seasonId`는 lowercase canonical UUID이며 version은 `1..5`, variant 첫 문자는
  `8`, `9`, `a`, `b` 중 하나다. 이후 이 문서의 `canonical UUID`도 같은 문법을 뜻한다.
- `roomId`는 lowercase `4-4-4` 형식이며 alphabet은
  `abcdefghjkmnpqrstuvwxyz23456789`로 고정한다.
- producer는 앞뒤 공백, trailing slash, 대문자 UUID·room ID 또는 비canonical 구분자를
  보내지 않는다. GO도 이를 canonical 값으로 고쳐 저장하지 않고 거부해야 한다.
- query, fragment, percent encoding, scheme, authority, 역슬래시, 반복 slash와 dot segment는
  허용하지 않는다.
- 신뢰 origin은 path·query·fragment가 없는 HTTP(S) origin이다. 운영 환경은 HTTPS만 쓴다.
- `BATON + MEETING_ENTRY`, `ROUND + NAVIGATION`을 포함한 표 밖의 모든 조합은
  `400 INVALID_LINK`로 거부한다.
- 알려지지 않은 enum 문자열처럼 JSON 자체가 역직렬화되지 않는 입력은 PRD-0002에 따라
  `400 INVALID_REQUEST`다. `INVALID_LINK`는 알려진 값으로 만든 비허용 조합과 locator에 쓴다.
- `RESOURCE_OPEN`은 wire·DB 호환성을 위해 enum 값만 예약하고 v1 생성에는 허용하지 않는다.
  안정적인 resource landing route가 생기면 새 계약 버전으로 추가한다.

`NAVIGATION`과 `MEETING_ENTRY`는 권한 등급이 아니다. 목적에 맞지 않는 path에 purpose만
바꿔 붙이는 방식으로 계약을 확장할 수 없다.

## 4. BATON landing 계약

BATON v1 locator는 실제 UI route인 `/teams/{teamId}/seasons/{seasonId}`다. 현재 BATON의
신규 브라우저 공유 URL은 같은 경로의 fragment에 `#accessKey=...`를 포함하지만, 그 값은
workspace credential이므로 GO에 전달하거나 저장할 수 없다.

따라서 `BATON + NAVIGATION`의 v1 의미는 다음으로 제한한다.

1. 브라우저가 해당 team의 검증된 access key를 BATON local storage에 이미 보유한다.
2. GO는 credential 없는 canonical BATON path로만 리다이렉트한다.
3. BATON이 저장된 access key로 workspace를 조회하고 team·season 접근을 최종 판단한다.
4. key가 없는 브라우저에는 BATON이 기존 공유 링크로 다시 접근하라는 안내를 표시한다.

key가 없는 신규 브라우저에 GO 링크만 전달해 초대하거나 권한을 부여하는 흐름은 v1에서
지원하지 않는다. 향후 계정 로그인, 초대 claim 또는 인증된 교환 landing이 생기면 별도
계약으로 추가한다. 그때도 access key나 session을 GO target에 넣지 않는다.

## 5. ROUND landing과 participation grant 계약

`ROUND + MEETING_ENTRY`는 `/room/{roomId}`의 landing과 pre-join UI를 여는 계약이다. 공개
`GET`만으로 signaling 입장을 허가하지 않으며 카메라·마이크 권한도 사용자 pre-join 동작
전에 요청하지 않는다.

### 5.1 동일 public origin과 edge topology

BATON mode에서 BATON과 ROUND는 브라우저에 **하나의 동일한 HTTPS origin**으로 보여야 한다.
운영의 `BATON_GO_BATON_BASE_URL`과 `BATON_GO_ROUND_BASE_URL`은 scheme, host와 port까지 같은
BATON public origin으로 설정한다. `targetSystem`은 논리적 route owner를 구분할 뿐 별도
host를 뜻하지 않는다. 로컬 기본값의 서로 다른 `5173`, `5174` port는 독립 개발 편의값이며
BATON-mode E2E 계약을 만족하지 않는다.

outer edge는 더 구체적인 경로를 먼저 평가해 다음과 같이 라우팅한다.

| 용도 | 브라우저 경로 | 소유·라우팅 |
| --- | --- | --- |
| ROUND landing HTML | `GET /room/{roomId}` | BATON 전용 `round-baton-web` SPA |
| ROUND 정적 asset | `GET /round-ui/**` | BATON 전용 `round-baton-web` asset |
| BATON session | `GET /api/v1/auth/session` | BATON이 직접 처리 |
| grant refresh | `POST /round/rooms/{roomId}/participation-grant/refresh` | BATON이 직접 처리하며 ROUND로 proxy하지 않음 |
| signaling | `/round/rooms/{roomId}/signal` | edge가 ROUND `/rooms/{roomId}/signal`로 전달 |
| TURN | `POST /round/rooms/{roomId}/turn-credentials` | edge가 ROUND `/api/rooms/{roomId}/turn-credentials`로 전달 |

grant refresh exact route는 generic `/round/**` proxy보다 우선한다. `/room/**`가 BATON SPA로,
`/round-ui/**`가 standalone ROUND asset으로 잘못 라우팅되는 배포도 허용하지 않는다.

### 5.2 BATON session과 CSRF 조회

브라우저는 `cache: no-store`, `credentials: same-origin`, `redirect: error`로
`GET /api/v1/auth/session`을 호출한다. 인증된 `200 OK` 응답은
`Cache-Control: no-store`와 다음 필드를 포함한다.

```json
{
  "authenticated": true,
  "accountId": "8e448211-66ae-44ab-9888-c4960648c22b",
  "csrfHeaderName": "X-CSRF-TOKEN",
  "csrfToken": "opaque-csrf-token"
}
```

- `accountId`는 3절의 canonical UUID다.
- `csrfHeaderName`은 1..128자의 RFC token 문자만 사용한다. `accept`, `authorization`,
  `cache-control`, `connection`, `content-length`, `content-type`, `cookie`, `host`, `origin`,
  `referer`, `set-cookie`와 `proxy-`, `sec-`, `x-forwarded-` prefix는 대소문자와 관계없이
  허용하지 않는다.
- `csrfToken`은 비어 있지 않은 4096자 이하 문자열이다.
- session이 없으면 `200 OK`, `Cache-Control: no-store`, 정확한
  `{"authenticated":false}`를 반환하며 CSRF와 account 필드를 포함하지 않는다.

이 GET은 session을 새로 발급하거나 workspace 권한을 부여하지 않는다.

### 5.3 authoritative room mapping과 refresh 요청

BATON mode의 admission 순서는 다음과 같다.

1. 브라우저가 5.2의 BATON session과 CSRF 값을 확인한다.
2. 동적 CSRF header와 same-origin credential을 사용해
   `POST /round/rooms/{roomId}/participation-grant/refresh`를 호출한다.
3. BATON은 현재 계정의 team·season meeting 참여 권한과 authoritative resource-room 매핑을
   확인한 뒤 짧은 수명의 participation grant를 발급한다.
4. 브라우저는 refresh 성공 뒤에만 TURN credential을 요청하고 WebSocket을 연결한다.
5. ROUND는 URL, cookie claim과 signaling join payload의 room ID가 모두 같을 때만 입장을
   허용한다.

BATON은 각 `roomId`를 정확히 하나의 active `(teamId, seasonId, resourceId)` mapping에만
연결한다. 하나의 resource도 동시에 하나의 active room만 가진다. mapping을 종료하면
room ID tombstone을 영구 보존하고 다른 team·season·resource에 재사용하지 않는다. mapping은
GO 링크 생성 전에 commit하며, 종료 순간부터 grant refresh를 거부하고 GO 링크 폐기를
비동기로 수렴시킨다.

현재 BATON `RoleResource`의 임의 URL 필드는 이 authoritative mapping이 아니다. room
admission용 전용 aggregate나 동등한 DB 제약을 추가해 active `roomId`와 active `resourceId`
양쪽의 유일성을 transaction 안에서 보장해야 한다.

BATON UI가 같은 origin의 session storage에 다음 entry locator를 둘 수 있다.

```json
{
  "version": 1,
  "teamId": "canonical UUID",
  "seasonId": "canonical UUID",
  "resourceId": "canonical UUID",
  "roomId": "canonical ROUND room ID"
}
```

key는 `baton-round-entry:v1:{roomId}`다. 이 값은 선택적인 locator hint이며 credential이나
권한 증명이 아니다. BATON은 값이 있으면 authoritative mapping과 exact match인지 다시
검증하고, 값이 없으면 path의 room ID로 mapping을 찾는다.

hint가 있으면 refresh body는 path의 `roomId`를 반복하지 않고 다음 세 필드만 보낸다. hint가
없으면 `Content-Type`과 body를 모두 보내지 않는다. body가 있으면 아래 세 필드 외의 값은
`400 INVALID_INPUT`이다.

```json
{
  "teamId": "canonical UUID",
  "seasonId": "canonical UUID",
  "resourceId": "canonical UUID"
}
```

BATON은 정확한 동일 출처 `Origin`, `Sec-Fetch-Site: same-origin`, 유효한 session과 동적 CSRF
header를 모두 요구하고 CORS를 열지 않는다.

### 5.4 refresh 응답과 cookie

성공은 `200 OK`, `Cache-Control: no-store`, `X-Request-Id`와 추가 필드 없는 다음 두
정수 필드를 반환한다.

```json
{
  "expiresAt": 1780000000,
  "refreshAfterSeconds": 240
}
```

`expiresAt`은 새 JWT `exp`와 같은 Unix epoch seconds이며 cookie 만료도 같은 시각을 넘지
않는다. v1은 `exp - iat = 300`초와 `refreshAfterSeconds = 240`초로 고정한다. 이는 TURN
credential 검증에 필요한 최소 잔여 60초와 refresh·TURN 왕복 시간 사이의 여유를 보장한다.
브라우저는 wall clock 차이로 이를 다시 계산하지 않고 수신 시점부터
`refreshAfterSeconds`의 monotonic 상대 시간을 사용한다.

성공마다 fresh `jti`와 expiry로 다음 cookie를 회전한다. JWT는 응답 body나 JavaScript에
반환하지 않는다.

```text
Name: __Secure-round_access
HttpOnly; Secure; SameSite=Strict
Path=/round/rooms/{roomId}
Domain 생략(host-only)
```

실패 body는 BATON 공통 `{code, message}` 형식이며 모두 `Cache-Control: no-store`와
`X-Request-Id`를 반환한다.

| 조건 | status와 code | cookie 처리 |
| --- | --- | --- |
| body·room ID 형식 오류 | `400 INVALID_INPUT` | 기존 cookie 유지 |
| BATON session 없음 | `401 AUTHENTICATION_REQUIRED` | 같은 path의 cookie 만료 |
| Origin·Fetch Metadata·CSRF 거부 | `403 REQUEST_FORBIDDEN` | 기존 cookie 유지 |
| meeting 참여 권한 없음 | `403 ROUND_PARTICIPATION_DENIED` | 같은 path의 cookie 만료 |
| active room mapping 없음 | `404 ROUND_ROOM_NOT_FOUND` | 같은 path의 cookie 만료 |
| pre-auth 처리 한도 초과 | `429 RATE_LIMIT_EXCEEDED`와 `Retry-After` | 기존 cookie 유지 |
| 예상하지 못한 오류 | `500 INTERNAL_ERROR` | 기존 cookie 유지 |

브라우저 session용 `401`에는 서비스 Bearer 인증을 암시하는 `WWW-Authenticate`를 넣지 않는다.
어떤 비성공 응답도 새 grant를 발급하지 않으며 브라우저는 TURN·WebSocket 시작을 중단한다.
만료 응답은 같은 cookie name·Path·보안 속성과 `Max-Age=0`을 사용한다. cookie를 만료해도
이미 연결된 socket은 handshake 당시 grant의 `exp`까지 유지될 수 있다.

### 5.5 JWT와 claim binding

BATON은 참여권을 `RS256`으로만 서명하고 JOSE header에 BATON JWK Set의 공개키를 식별하는
비어 있지 않은 `kid`를 넣는다. ROUND는 `RS256`, `kid`에 해당하는 공개키, 설정된 `iss`,
`aud=round`, 모든 필수 claim, `exp > iat`, 최대 300초의 `exp - iat`와 최대 60초의 미래
`iat`만 허용한다.

v1 claim 값은 다음과 같이 고정한다.

| claim | v1 값 |
| --- | --- |
| `sub` | 인증된 BATON `accountId` canonical UUID |
| `study_id` | authoritative mapping의 `teamId` canonical UUID |
| `jti` | refresh마다 새로 만든 canonical UUID |
| `room_id` | path와 mapping의 canonical ROUND `roomId` |
| `role` | v1 고정 `participant` |

현재 BATON에는 account-role 권한 모델이 없으므로 `host`를 추측해 발급하지 않는다. 명시적인
meeting host 권한과 매핑 규칙을 도입하는 새 계약 버전 전까지 `host`는 예약값이다.

키 교체는 새 공개키를 JWK Set에 먼저 게시한 뒤 새 `kid` 발급으로 전환한다. 이전 공개키는
기존 grant 최대 수명 300초와 clock skew 60초가 모두 지나고 ROUND JWK cache가 새 키를
관찰한 뒤 제거한다. 배포 전에 두 키의 overlap과 cache 갱신을 검증하며 ROUND에는 개인키를
배포하지 않는다.

URL path, JWT `room_id`, TURN·WebSocket path와 signaling `room.join.roomId`는 모두 같아야
한다. 현재 participation grant는 **one-time ticket이 아니다**. 동일 `jti`의 동시
WebSocket 사용은 제한하지만 연결 종료 뒤 만료 전 순차 재사용을 막지 않는다. 진정한
일회성 교환은 원자적 소비 저장소와 별도 인증 `POST` 계약으로 추가한다.

ROUND는 진행 중인 handshake와 활성 socket을 합쳐 동일 `jti`당 하나, 동일
`(room_id, sub)`당 두 연결까지만 허용한다. 두 번째 사용자-방 slot은 fresh `jti`로 갱신한
정상 재연결 overlap용이며 초과 요청은 기존 연결을 끊지 않고 `429`로 거부한다. socket은
handshake 당시 grant의 immutable lease에 묶이고 `exp`에 `4001 / Participation grant
expired`로 닫힌다. TURN credential도 해당 grant `exp`보다 늦게 만료될 수 없다.

### 5.6 edge와 signaling 운영 경계

- edge는 WebSocket upgrade와 브라우저의 원본 `Origin`을 보존하고 신뢰 Origin을 합성하지
  않는다. client가 보낸 `Forwarded`와 `X-Forwarded-*`는 제거한 뒤 canonical host, HTTPS
  scheme과 client address를 edge가 다시 설정한다.
- ROUND `ALLOWED_ORIGINS`에는 BATON의 exact public HTTPS origin만 둔다. wildcard, `null`,
  non-loopback HTTP와 CORS fallback은 허용하지 않는다.
- web은 `round-baton-web` flavor와 `VITE_ROUND_AUTH_MODE=baton`을 사용하고 endpoint override를
  비운다. signaling도 `ROUND_AUTH_MODE=baton`과 production profile로 시작하며 JWK·issuer
  설정 누락을 standalone fallback으로 처리하지 않는다.
- signaling은 `ROUND_AUTH_MAX_GRANT_LIFETIME_SECONDS=300`, 계약된 exact issuer,
  `ROUND_AUTH_AUDIENCE=round`와 HTTPS JWK Set URI를 사용한다. edge는 BATON의 `Set-Cookie`와
  브라우저의 room-scoped grant cookie를 제거하거나 다른 path로 다시 쓰지 않는다.
- standalone 공개 `/signal`, `/api/turn-credentials`와 standalone web image는 BATON public
  edge에 노출하지 않는다.
- refresh, signaling, TURN의 세 room-scoped 공개 경로에는 6인 정상 refresh·재연결 burst를
  허용하는 bounded pre-auth rate limit을 둔다.
- Java signaling port는 edge와 private monitoring network만 접근하고 host, public load
  balancer와 security group에 노출하지 않는다.
- room·admission·quota state를 외부화하기 전에는 signaling을 정확히 한 replica로 운영한다.
- BATON page의 `Permissions-Policy`는 camera, microphone, display-capture를 허용하고 CSP
  `connect-src`에 room-scoped WSS path를 포함한다.

## 6. 생성·재시도·폐기 계약

브라우저는 GO 관리 API를 호출하지 않는다. 신뢰된 서버 호출자는 다음 순서를 지킨다.

1. 원본 aggregate와 locator 매핑을 먼저 commit한다.
2. 생성 intent용 canonical UUID를 원본 상태 또는 outbox에 영속화한다.
3. 원본 DB transaction 밖에서 `POST /api/v1/links`를 호출한다.
4. timeout, 연결 실패와 재시도 가능한 `5xx`는 backoff와 jitter를 적용해 같은
   `Idempotency-Key`와 동일한 canonical payload 값으로 재시도한다.
5. `201` 또는 replay `200`을 받으면 GO `linkId`와 사용자에게 표시할 short URL을 필요한
   범위에서 보관한다. 전체 short URL, 코드와 멱등성 키는 로그에 남기지 않는다.

호출자는 결과를 다음 상태로 구분한다.

| 결과 | 호출자 상태와 처리 |
| --- | --- |
| `201`·replay `200` | `ACTIVE`; cancel tombstone이 있으면 공개하지 않고 즉시 revoke |
| 이전 ambiguous 결과가 없는 확정적 `400`·`401` | `CREATE_REJECTED/NO_LINK`; 호출자·인증 오류를 고치되 cancel은 완료 가능 |
| timeout·연결 실패·그 밖의 일시적 `5xx` | `CREATE_UNKNOWN`; link가 존재할 수 있으므로 같은 key와 payload로 재시도 |
| `409 IDEMPOTENCY_KEY_REUSED` | `CREATE_CONFLICT`; 다른 payload의 link를 임의 revoke하지 않고 운영자 조사 |
| `LINK_CODE_REPLAY_UNAVAILABLE`·`LINK_CODE_CONFIGURATION_MISMATCH` | `RECOVERY_REQUIRED`; 설정 복구 뒤 같은 key로 존재 여부와 linkId를 회수 |

한 번이라도 `CREATE_UNKNOWN`이 된 intent에는 뒤의 `400`·`401`만으로 `NO_LINK`를 선언하지
않는다. credential·배포 차이를 복구한 뒤 replay하거나 운영 inventory로 부재를 증명한다.

원본 resource가 삭제·종료·권한 회수되면 호출자는 outbox나 동등한 재시도 가능한 방식으로
GO 폐기를 요청한다. 폐기가 지연되는 동안에도 BATON 또는 ROUND의 최종 권한 검사가 접근을
막아야 한다. target 변경은 기존 링크를 수정하지 않고 새 intent로 생성한 뒤 이전 링크를
폐기한다.

생성 결과를 받기 전에 원본이 취소되면 호출자는 intent에 `CANCEL_REQUESTED` tombstone을
남긴다. `CREATE_UNKNOWN`은 같은 key로 create/replay를 수렴시켜 `linkId`를 얻은 즉시 short
URL을 노출하기 전에 revoke한다. 확정적 `CREATE_REJECTED/NO_LINK`는 revoke 없이 cancel을
완료한다. `CREATE_CONFLICT`와 `RECOVERY_REQUIRED`는 운영자가 원래 payload와 존재 여부를
확정할 때까지 cancel을 완료하지 않는다. create와 revoke outbox는 intent별 순서를 보장한다.

## 7. 실패와 보상 의미

| 상황 | 계약된 결과 |
| --- | --- |
| 원본 commit 뒤 GO 생성 실패 | 원본 transaction을 rollback하지 않고 동일 intent를 재시도하며 링크 상태를 pending으로 유지 |
| GO가 저장했지만 응답 유실 | 동일 key와 payload replay로 같은 링크를 복구 |
| create pending 중 원본 취소 | `CREATE_UNKNOWN`이면 linkId 회수 뒤 폐기, 확정적 `NO_LINK`면 즉시 완료, conflict/recovery면 운영 확인 |
| GO resolver 장애 | 링크 열기만 실패하며 BATON·ROUND의 직접 조회·편집은 계속 가능 |
| BATON·ROUND 장애 | GO는 fallback origin이나 다른 target으로 보내지 않음 |
| 원본 권한 회수 뒤 GO 폐기 지연 | 대상 서비스가 즉시 거부하고 GO 폐기는 비동기로 수렴 |
| 존재하지 않는 team·season·room | 대상 서비스가 자신의 안전한 not-found/unauthorized 응답을 반환 |
| bot·메신저의 선조회 | landing만 열 수 있고 권한·participation grant를 소비하거나 발급하지 않음 |

GO는 resolution마다 BATON 또는 ROUND를 동기 조회하지 않는다. 대상 서비스도 자신의 일반
projection 조회에서 링크별로 GO를 호출하지 않는다.

## 8. 호환성과 기존 링크 정책

- `BATON`, `ROUND`, `NAVIGATION`, `MEETING_ENTRY`, `RESOURCE_OPEN` 문자열은 wire와 DB 값으로
  동결한다. 이름 변경·삭제는 migration을 포함한 별도 breaking change다.
- v1 route owner는 아직 유효한 GO 링크가 있는 동안 canonical route를 유지하거나, 새 링크
  발급과 이전 링크 폐기를 함께 배포한다.
- 계약 강화 전에 생성된 링크를 자동 grandfathering하지 않는다. rollout 전에 inventory해
  표의 canonical 조합과 일치하는 링크만 유지하고 나머지는 폐기·재발급한다.
- resolver가 DB의 계약 위반 target을 발견하면 존재 여부를 드러내지 않도록
  `404 LINK_NOT_FOUND`로 응답하고 `Location`을 반환하지 않는다. 수동 DB 적재나 잘못된
  migration도 우회 경로가 되어서는 안 된다.
- GO resolver는 저장된 enum을 raw 문자열로 읽은 뒤 같은 exact 정책을 적용한다. 따라서
  알려진 값의 비허용 조합뿐 아니라 알 수 없는 enum도 `GET`과 `HEAD`에서 같은
  `404 LINK_NOT_FOUND`로 숨기고 redirect하지 않는다.
- 저장 target 계약 위반마다
  `baton.go.public.resolver.target.contract.violations` metric을 증가시키고 linkId와 requestId만
  포함한 안전한 로그를 남긴다. 공개 코드, target path와 전체 short URL은 metric tag나 로그에
  넣지 않는다.
- 새 target system, purpose 조합 또는 path template는 PRD·ADR·도메인 정책·HTTP 계약 테스트와
  대상 서비스 E2E를 함께 변경해야 한다. origin 설정만 추가해서 확장하지 않는다.

## 9. 현재 구현 상태와 운영 gate

| 항목 | 현재 상태 | 공개 전 필수 조치 |
| --- | --- | --- |
| GO exact target 계약 | 생성·resolution과 위반 metric·안전 로그 구현됨 | 계약 전 저장 데이터를 inventory하고 비허용 링크 폐기·재발급 |
| BATON workspace route | 구현됨 | v1은 기존 access key 보유 브라우저 복귀로만 노출 |
| BATON 신규 브라우저 진입 | 미구현 | 계정·초대·claim landing 계약 전에는 지원 표시 금지 |
| ROUND landing·pre-join | 구현됨 | canonical path E2E 고정 |
| BATON-mode 동일 origin | 미연결 | GO의 BATON·ROUND origin을 같은 public HTTPS origin으로 고정하고 전체 edge route 검증 |
| ROUND BATON-mode browser·signaling 검증 | 구현됨 | BATON session·refresh 발급과 edge routing 연결 |
| BATON account session·grant 발급 | 미구현 | 5절의 session, CSRF, mapping, participant role, JWK와 cookie 계약 구현 |
| BATON room mapping | 미구현 | one-to-one active mapping, 영구 room tombstone과 `study_id=teamId` 강제 |
| edge admission 보안 | 미연결 | exact Origin, no CORS, cookie 보존, 전달 header 정리, route 우선순위와 pre-auth limit 검증 |
| 호출자 원격 생성·폐기 | 미구현 | ordered outbox, idempotent worker, cancel tombstone과 create→revoke 수렴 구현 |
| ROUND signaling 확장 | 단일 process 상태 | 외부 상태 저장 전에는 단일 signaling replica로 운영 |

이 표의 미구현·미연결 항목과 완료되지 않은 계약 전 데이터 inventory 때문에 현재 공개
production rollout은 승인되지 않는다.

## 10. 계약 검증 시나리오

- 두 허용 조합의 exact canonical path만 생성되고 replay payload 비교에도 같은 값이 사용된다.
- UUID 대문자, ROUND 금지 alphabet, trailing slash, 추가 segment와 표 밖 purpose 조합은
  `400 INVALID_LINK`이며 DB에 쓰이지 않는다.
- legacy 또는 수동 적재된 비허용 target은 resolution에서 `404 LINK_NOT_FOUND`이며
  redirect되지 않는다.
- BATON access key가 없는 신규 브라우저에 GO가 권한을 부여하거나 credential을 노출하지
  않는다.
- ROUND prefetch만으로 participation grant, TURN credential 또는 WebSocket admission이
  발생하지 않는다.
- BATON·ROUND GO origin이 다르거나 edge route 우선순위가 틀리면 배포 검증이 실패한다.
- session·CSRF 필수 제약이나 refresh body·응답의 exact wire 형식과 다르면 fail-closed 한다.
- 동일 room의 두 active mapping, tombstone room 재사용, `study_id != teamId` 또는 v1 `host`
  claim 발급은 거부한다.
- grant refresh 실패, cookie/JWT 만료 불일치, 미게시 `kid` 또는 room claim 불일치 때 TURN과
  signaling 연결이 시작되지 않는다.
- 300초 이외 grant lifetime, 240초 이외 refresh delay 또는 60초 미만 TURN 잔여 수명은
  배포·계약 테스트에서 거부한다.
- JWK rotation은 새 키 선게시와 이전 키 360초 이상 overlap을 검증한다.
- 원격 생성 timeout 뒤 같은 intent replay가 하나의 link만 만들고, 원본 권한 회수는 대상
  거부와 GO 폐기로 수렴한다.
- create 결과를 모르는 상태에서 취소해도 linkId 회수 뒤 revoke되어 활성 orphan link가
  남지 않는다.
