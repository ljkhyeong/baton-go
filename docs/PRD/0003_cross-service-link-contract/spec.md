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
- 대상 조합, 위치 식별자 문법과 클릭 시점 권한 경계는 이 문서를 우선한다.
- 이 계약의 확정은 통합 구현 완료나 운영 배포 승인을 뜻하지 않는다. 9절의 관문을 모두
  통과하기 전에는 해당 흐름을 공개하지 않는다.

## 2. 용어와 소유권

`targetPath`는 URL 전체가 아니라 **설정된 신뢰 출처 기준의 절대 경로**다.
GO는 `targetSystem`으로 출처를 선택하고 정규 `targetPath`를 그대로 붙인다.

| 주체 | 소유하는 계약 | 소유하지 않는 계약 |
| --- | --- | --- |
| BATON GO | 공개 코드, 활성·만료·폐기, 허용 대상 조합과 위치 식별자 검증, 신뢰 출처 리다이렉트 | BATON 작업 공간 권한, ROUND 방 입장, 사용자 세션과 참여권 |
| BATON | 팀·시즌·리소스와 방 매핑, 방 삭제 표식, 작업 공간 접근 키, 향후 계정 세션·멤버십, ROUND 참여권·JWK 발급 | GO 공개 코드와 링크 수명주기, ROUND 시그널링 입장 |
| ROUND | 정규 방 ID, 입장 전 확인, 참여권 검증, 피어·시그널링·TURN 입장 | BATON 사용자·멤버십, GO 링크 상태 |
| 생성 호출자 | 원본 애그리게이트, 생성 의도 UUID, 재시도·보상·폐기 요청 | GO 내부 링크 코드와 대상 서비스의 최종 권한 |

위치 식별자의 ID는 위치를 찾기 위한 값일 뿐 권한 증명이 아니다. BATON 접근 키, 관리
Bearer 자격 증명, 사용자 세션 식별자, CSRF 토큰, ROUND 참여 허가와 TURN
자격 증명은 GO의 요청·DB·대상 경로·로그에 들어가면 안 된다.

## 3. v1 허용 조합과 정규 위치 식별자

다음 두 행만 v1에서 유효하다.

| `targetSystem` | `purpose` | 정규 `targetPath` | 의미 |
| --- | --- | --- | --- |
| `BATON` | `NAVIGATION` | `/teams/{teamId}/seasons/{seasonId}` | 이미 작업 공간 권한을 보유한 브라우저의 복귀 |
| `ROUND` | `MEETING_ENTRY` | `/room/{roomId}` | 방 입장 전 확인과 입장 절차를 시작하는 도착 화면 |

정확한 전송 형식은 다음과 같다.

```text
BATON
^/teams/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/seasons/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$

ROUND
^/room/[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}$
```

- `teamId`와 `seasonId`는 소문자 정규 UUID이며 버전은 `1..5`, 변형의 첫 문자는
  `8`, `9`, `a`, `b` 중 하나다. 이후 이 문서의 정규 UUID도 같은 문법을 뜻한다.
- `roomId`는 소문자 `4-4-4` 형식이며 문자 집합은
  `abcdefghjkmnpqrstuvwxyz23456789`로 고정한다.
- 생산자는 앞뒤 공백, 끝 슬래시, 대문자 UUID·방 ID 또는 비정규 구분자를
  보내지 않는다. GO도 이를 정규 값으로 고쳐 저장하지 않고 거부해야 한다.
- 쿼리, 프래그먼트, 퍼센트 인코딩, 스킴, 권한부, 역슬래시, 반복 슬래시와 점 경로 구간은
  허용하지 않는다.
- 신뢰 출처는 사용자 정보·경로·쿼리·프래그먼트가 없는 HTTP(S) 출처다. 명시적 포트는
  `1..65535`만 허용하며 운영 환경은 HTTPS만 쓴다.
- `BATON + MEETING_ENTRY`, `ROUND + NAVIGATION`을 포함한 표 밖의 모든 조합은
  `400 INVALID_LINK`로 거부한다.
- 알려지지 않은 열거형 문자열처럼 JSON 자체가 역직렬화되지 않는 입력은 PRD-0002에 따라
  `400 INVALID_REQUEST`다. `INVALID_LINK`는 알려진 값으로 만든 비허용 조합과 위치 식별자에 쓴다.
- `RESOURCE_OPEN`은 전송·DB 호환성을 위해 열거형 값만 예약하고 v1 생성에는 허용하지 않는다.
  안정적인 리소스 도착 경로가 생기면 새 계약 버전으로 추가한다.

`NAVIGATION`과 `MEETING_ENTRY`는 권한 등급이 아니다. 목적에 맞지 않는 경로에 목적만
바꿔 붙이는 방식으로 계약을 확장할 수 없다.

## 4. BATON 도착 화면 계약

BATON v1 위치 식별자는 실제 화면 경로인 `/teams/{teamId}/seasons/{seasonId}`다. 현재 BATON의
신규 브라우저 공유 URL은 같은 경로의 프래그먼트에 `#accessKey=...`를 포함하지만, 그 값은
작업 공간 자격 증명이므로 GO에 전달하거나 저장할 수 없다.

따라서 `BATON + NAVIGATION`의 v1 의미는 다음으로 제한한다.

1. 브라우저가 해당 팀의 검증된 접근 키를 BATON 로컬 저장소에 이미 보유한다.
2. GO는 자격 증명 없는 정규 BATON 경로로만 리다이렉트한다.
3. BATON이 저장된 접근 키로 작업 공간을 조회하고 팀·시즌 접근을 최종 판단한다.
4. 키가 없는 브라우저에는 BATON이 기존 공유 링크로 다시 접근하라는 안내를 표시한다.

키가 없는 신규 브라우저에 GO 링크만 전달해 초대하거나 권한을 부여하는 흐름은 v1에서
지원하지 않는다. 향후 계정 로그인, 초대 수령 또는 인증된 교환 도착 화면이 생기면 별도
계약으로 추가한다. 그때도 접근 키나 세션을 GO 대상에 넣지 않는다.

## 5. ROUND 도착 화면과 참여 허가 계약

`ROUND + MEETING_ENTRY`는 `/room/{roomId}`의 도착 화면과 입장 전 확인 화면을 여는 계약이다. 공개
`GET`만으로 시그널링 입장을 허가하지 않으며 카메라·마이크 권한도 사용자 입장 전 확인 동작
전에 요청하지 않는다.

### 5.1 동일 공개 출처와 경계 구성

BATON 모드에서 BATON과 ROUND는 브라우저에 **하나의 동일한 HTTPS 출처**로 보여야 한다.
운영의 `BATON_GO_BATON_BASE_URL`과 `BATON_GO_ROUND_BASE_URL`은 스킴, 호스트와 포트까지 같은
BATON 공개 출처로 설정한다. `targetSystem`은 논리적 경로 소유자를 구분할 뿐 별도
호스트를 뜻하지 않는다. 로컬 기본값의 서로 다른 `5173`, `5174` 포트는 독립 개발 편의값이며
BATON 모드 E2E 계약을 만족하지 않는다.
GO 설정의 로컬 예외는 `localhost`, 선행 0이 없는 정규 점 구분 십진 IPv4
`127.0.0.0/8`과 IPv6 루프백 리터럴에만 적용한다. 브라우저와 서버의 주소 해석이 달라질
수 있는 비정규 IPv4 표기는 루프백으로 간주하지 않는다.

외부 경계는 더 구체적인 경로를 먼저 평가해 다음과 같이 라우팅한다.

| 용도 | 브라우저 경로 | 소유·라우팅 |
| --- | --- | --- |
| ROUND 도착 HTML | `GET /room/{roomId}` | BATON 전용 `round-baton-web` SPA |
| ROUND 정적 자산 | `GET /round-ui/**` | BATON 전용 `round-baton-web` 자산 |
| BATON 세션 | `GET /api/v1/auth/session` | BATON이 직접 처리 |
| 참여 허가 갱신 | `POST /round/rooms/{roomId}/participation-grant/refresh` | BATON이 직접 처리하며 ROUND로 프록시하지 않음 |
| 시그널링 | `/round/rooms/{roomId}/signal` | 경계가 ROUND `/rooms/{roomId}/signal`로 전달 |
| TURN | `POST /round/rooms/{roomId}/turn-credentials` | 경계가 ROUND `/api/rooms/{roomId}/turn-credentials`로 전달 |

참여 허가 갱신의 정확한 경로는 범용 `/round/**` 프록시보다 우선한다. `/room/**`가 BATON SPA로,
`/round-ui/**`가 독립형 ROUND 자산으로 잘못 라우팅되는 배포도 허용하지 않는다.

### 5.2 BATON 세션과 CSRF 조회

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

- `accountId`는 3절의 정규 UUID다.
- `csrfHeaderName`은 1..128자의 RFC 토큰 문자만 사용한다. `accept`, `authorization`,
  `cache-control`, `connection`, `content-length`, `content-type`, `cookie`, `host`, `origin`,
  `referer`, `set-cookie`와 `proxy-`, `sec-`, `x-forwarded-` 접두사는 대소문자와 관계없이
  허용하지 않는다.
- `csrfToken`은 비어 있지 않은 4096자 이하 문자열이다.
- 세션이 없으면 `200 OK`, `Cache-Control: no-store`, 정확한
  `{"authenticated":false}`를 반환하며 CSRF와 계정 필드를 포함하지 않는다.

이 `GET`은 세션을 새로 발급하거나 작업 공간 권한을 부여하지 않는다.

### 5.3 권위 있는 방 매핑과 갱신 요청

BATON 모드의 입장 순서는 다음과 같다.

1. 브라우저가 5.2의 BATON 세션과 CSRF 값을 확인한다.
2. 동적 CSRF 헤더와 동일 출처 자격 증명을 사용해
   `POST /round/rooms/{roomId}/participation-grant/refresh`를 호출한다.
3. BATON은 현재 계정의 팀·시즌 회의 참여 권한과 권위 있는 리소스-방 매핑을
   확인한 뒤 짧은 수명의 참여 허가를 발급한다.
4. 브라우저는 갱신 성공 뒤에만 TURN 자격 증명을 요청하고 WebSocket을 연결한다.
5. ROUND는 URL, 쿠키 클레임과 시그널링 입장 요청 내용의 방 ID가 모두 같을 때만 입장을
   허용한다.

BATON은 각 `roomId`를 정확히 하나의 활성 `(teamId, seasonId, resourceId)` 매핑에만
연결한다. 하나의 리소스도 동시에 하나의 활성 방만 가진다. 매핑을 종료하면
방 ID 삭제 표식을 영구 보존하고 다른 팀·시즌·리소스에 재사용하지 않는다. 매핑은
GO 링크 생성 전에 커밋하며, 종료 순간부터 참여 허가 갱신을 거부하고 GO 링크 폐기를
비동기로 수렴시킨다.

현재 BATON `RoleResource`의 임의 URL 필드는 이 권위 있는 매핑이 아니다. 방
입장용 전용 애그리게이트나 동등한 DB 제약을 추가해 활성 `roomId`와 활성 `resourceId`
양쪽의 유일성을 트랜잭션 안에서 보장해야 한다.

BATON UI가 같은 출처의 세션 저장소에 다음 진입 위치 식별자를 둘 수 있다.

```json
{
  "version": 1,
  "teamId": "정규 UUID",
  "seasonId": "정규 UUID",
  "resourceId": "정규 UUID",
  "roomId": "정규 ROUND 방 ID"
}
```

키는 `baton-round-entry:v1:{roomId}`다. 이 값은 선택적인 위치 식별자 참고값이며 자격 증명이나
권한 증명이 아니다. BATON은 값이 있으면 권위 있는 매핑과 정확히 일치하는지 다시
검증하고, 값이 없으면 경로의 방 ID로 매핑을 찾는다.

참고값이 있으면 갱신 요청 본문은 경로의 `roomId`를 반복하지 않고 다음 세 필드만 보낸다. 참고값이
없으면 `Content-Type`과 요청 본문을 모두 보내지 않는다. 요청 본문이 있으면 아래 세 필드 외의 값은
`400 INVALID_INPUT`이다.

```json
{
  "teamId": "정규 UUID",
  "seasonId": "정규 UUID",
  "resourceId": "정규 UUID"
}
```

BATON은 정확한 동일 출처 `Origin`, `Sec-Fetch-Site: same-origin`, 유효한 세션과 동적 CSRF
헤더를 모두 요구하고 CORS를 열지 않는다.

### 5.4 갱신 응답과 쿠키

성공은 `200 OK`, `Cache-Control: no-store`, `X-Request-Id`와 추가 필드 없는 다음 두
정수 필드를 반환한다.

```json
{
  "expiresAt": 1780000000,
  "refreshAfterSeconds": 240
}
```

`expiresAt`은 새 JWT `exp`와 같은 Unix 기원 시각 이후의 초이며 쿠키 만료도 같은 시각을 넘지
않는다. v1은 `exp - iat = 300`초와 `refreshAfterSeconds = 240`초로 고정한다. 이는 TURN
자격 증명 검증에 필요한 최소 잔여 60초와 갱신·TURN 왕복 시간 사이의 여유를 보장한다.
브라우저는 실제 시계 차이로 이를 다시 계산하지 않고 수신 시점부터
`refreshAfterSeconds`의 단조 증가 상대 시간을 사용한다.

성공마다 새로운 `jti`와 만료 시각으로 다음 쿠키를 회전한다. JWT는 응답 본문이나 JavaScript에
반환하지 않는다.

```text
Name: __Secure-round_access
HttpOnly; Secure; SameSite=Strict
Path=/round/rooms/{roomId}
Domain 생략(host-only)
```

실패 응답 본문은 BATON 공통 `{code, message}` 형식이며 모두 `Cache-Control: no-store`와
`X-Request-Id`를 반환한다.

| 조건 | 상태와 코드 | 쿠키 처리 |
| --- | --- | --- |
| 요청 본문·방 ID 형식 오류 | `400 INVALID_INPUT` | 기존 쿠키 유지 |
| BATON 세션 없음 | `401 AUTHENTICATION_REQUIRED` | 같은 경로의 쿠키 만료 |
| `Origin`·Fetch Metadata·CSRF 거부 | `403 REQUEST_FORBIDDEN` | 기존 쿠키 유지 |
| 회의 참여 권한 없음 | `403 ROUND_PARTICIPATION_DENIED` | 같은 경로의 쿠키 만료 |
| 활성 방 매핑 없음 | `404 ROUND_ROOM_NOT_FOUND` | 같은 경로의 쿠키 만료 |
| 사전 인증 처리 한도 초과 | `429 RATE_LIMIT_EXCEEDED`와 `Retry-After` | 기존 쿠키 유지 |
| 예상하지 못한 오류 | `500 INTERNAL_ERROR` | 기존 쿠키 유지 |

브라우저 세션용 `401`에는 서비스 Bearer 인증을 암시하는 `WWW-Authenticate`를 넣지 않는다.
어떤 비성공 응답도 새 참여 허가를 발급하지 않으며 브라우저는 TURN·WebSocket 시작을 중단한다.
만료 응답은 같은 쿠키 이름·`Path`·보안 속성과 `Max-Age=0`을 사용한다. 쿠키를 만료해도
이미 연결된 소켓은 연결 수립 당시 참여 허가의 `exp`까지 유지될 수 있다.

### 5.5 JWT와 클레임 결합

BATON은 참여권을 `RS256`으로만 서명하고 JOSE 헤더에 BATON JWK Set의 공개키를 식별하는
비어 있지 않은 `kid`를 넣는다. ROUND는 `RS256`, `kid`에 해당하는 공개키, 설정된 `iss`,
`aud=round`, 모든 필수 클레임, `exp > iat`, 최대 300초의 `exp - iat`와 최대 60초의 미래
`iat`만 허용한다.

v1 클레임 값은 다음과 같이 고정한다.

| 클레임 | v1 값 |
| --- | --- |
| `sub` | 인증된 BATON `accountId` 정규 UUID |
| `study_id` | 권위 있는 매핑의 `teamId` 정규 UUID |
| `jti` | 갱신마다 새로 만든 정규 UUID |
| `room_id` | 경로와 매핑의 정규 ROUND `roomId` |
| `role` | v1 고정 `participant` |

현재 BATON에는 계정-역할 권한 모델이 없으므로 `host`를 추측해 발급하지 않는다. 명시적인
회의 주최자 권한과 매핑 규칙을 도입하는 새 계약 버전 전까지 `host`는 예약값이다.

키 교체는 새 공개키를 JWK Set에 먼저 게시한 뒤 새 `kid` 발급으로 전환한다. 이전 공개키는
기존 참여 허가 최대 수명 300초와 시계 오차 60초가 모두 지나고 ROUND JWK 캐시가 새 키를
관찰한 뒤 제거한다. 배포 전에 두 키의 중첩 기간과 캐시 갱신을 검증하며 ROUND에는 개인키를
배포하지 않는다.

URL 경로, JWT `room_id`, TURN·WebSocket 경로와 시그널링 `room.join.roomId`는 모두 같아야
한다. 현재 참여 허가는 **일회성 티켓이 아니다**. 동일 `jti`의 동시
WebSocket 사용은 제한하지만 연결 종료 뒤 만료 전 순차 재사용을 막지 않는다. 진정한
일회성 교환은 원자적 소비 저장소와 별도 인증 `POST` 계약으로 추가한다.

ROUND는 진행 중인 연결 수립과 활성 소켓을 합쳐 동일 `jti`당 하나, 동일
`(room_id, sub)`당 두 연결까지만 허용한다. 두 번째 사용자-방 연결 자리는 새로운 `jti`로 갱신한
정상 재연결 중첩용이며 초과 요청은 기존 연결을 끊지 않고 `429`로 거부한다. 소켓은
연결 수립 당시 참여 허가의 변경 불가능한 임대 기간에 묶이고 `exp`에 `4001 / Participation grant
expired`로 닫힌다. TURN 자격 증명도 해당 참여 허가의 `exp`보다 늦게 만료될 수 없다.

### 5.6 경계와 시그널링 운영 범위

- 경계는 WebSocket upgrade와 브라우저의 원본 `Origin`을 보존하고 신뢰 출처를 합성하지
  않는다. 클라이언트가 보낸 `Forwarded`와 `X-Forwarded-*`는 제거한 뒤 정규 호스트, HTTPS
  스킴과 클라이언트 주소를 경계가 다시 설정한다.
- ROUND `ALLOWED_ORIGINS`에는 BATON의 정확한 공개 HTTPS 출처만 둔다. 와일드카드, `null`,
  비루프백 HTTP와 CORS 대체 동작은 허용하지 않는다.
- 웹은 `round-baton-web` 구성 유형과 `VITE_ROUND_AUTH_MODE=baton`을 사용하고 엔드포인트 재정의를
  비운다. 시그널링도 `ROUND_AUTH_MODE=baton`과 운영 프로필로 시작하며 JWK·발급자
  설정 누락을 독립형 대체 동작으로 처리하지 않는다.
- 시그널링은 `ROUND_AUTH_MAX_GRANT_LIFETIME_SECONDS=300`, 계약된 정확한 발급자,
  `ROUND_AUTH_AUDIENCE=round`와 HTTPS JWK Set URI를 사용한다. 경계는 BATON의 `Set-Cookie`와
  브라우저의 방 한정 참여 허가 쿠키를 제거하거나 다른 경로로 다시 쓰지 않는다.
- 독립형 공개 `/signal`, `/api/turn-credentials`와 독립형 웹 이미지는 BATON 공개
  경계에 노출하지 않는다.
- 갱신, 시그널링, TURN의 세 방 한정 공개 경로에는 6인의 정상 갱신·재연결 급증을
  허용하는 상한 있는 사전 인증 요청률 제한을 둔다.
- Java 시그널링 포트는 경계와 비공개 모니터링 네트워크만 접근하고 호스트, 공개 로드
  밸런서와 보안 그룹에 노출하지 않는다.
- 방·입장·할당량 상태를 외부화하기 전에는 시그널링을 정확히 한 복제본으로 운영한다.
- BATON 페이지의 `Permissions-Policy`는 `camera`, `microphone`, `display-capture`를 허용하고 CSP
  `connect-src`에 방 한정 WSS 경로를 포함한다.

## 6. 생성·재시도·폐기 계약

브라우저는 GO 관리 API를 호출하지 않는다. 신뢰된 서버 호출자는 다음 순서를 지킨다.

1. 원본 애그리게이트와 위치 식별자 매핑을 먼저 커밋한다.
2. 생성 의도용 정규 UUID를 원본 상태 또는 아웃박스에 영속화한다.
3. 원본 DB 트랜잭션 밖에서 `POST /api/v1/links`를 호출한다.
4. 시간 초과, 연결 실패와 재시도 가능한 `5xx`는 backoff와 jitter를 적용해 같은
   `Idempotency-Key`와 동일한 정규 요청 내용으로 재시도한다.
5. `201` 또는 재생 `200`을 받으면 GO `linkId`와 사용자에게 표시할 단축 URL을 필요한
   범위에서 보관한다. 전체 단축 URL, 코드와 멱등성 키는 로그에 남기지 않는다.

호출자는 결과를 다음 상태로 구분한다.

| 결과 | 호출자 상태와 처리 |
| --- | --- |
| `201`·재생 `200` | `ACTIVE`; 취소 삭제 표식이 있으면 공개하지 않고 즉시 폐기 |
| 이전 불확실한 결과가 없는 확정적 `400`·`401` | `CREATE_REJECTED/NO_LINK`; 호출자·인증 오류를 고치되 취소는 완료 가능 |
| 시간 초과·연결 실패·그 밖의 일시적 `5xx` | `CREATE_UNKNOWN`; 링크가 존재할 수 있으므로 같은 키와 요청 내용으로 재시도 |
| `409 IDEMPOTENCY_KEY_REUSED` | `CREATE_CONFLICT`; 다른 요청 내용의 링크를 임의 폐기하지 않고 운영자 조사 |
| `LINK_CODE_REPLAY_UNAVAILABLE`·`LINK_CODE_CONFIGURATION_MISMATCH` | `RECOVERY_REQUIRED`; 설정 복구 뒤 같은 키로 존재 여부와 `linkId`를 회수 |

한 번이라도 `CREATE_UNKNOWN`이 된 의도에는 뒤의 `400`·`401`만으로 `NO_LINK`를 선언하지
않는다. 자격 증명·배포 차이를 복구한 뒤 재생하거나 운영 목록 조사로 부재를 증명한다.

원본 리소스가 삭제·종료·권한 회수되면 호출자는 아웃박스나 동등한 재시도 가능한 방식으로
GO 폐기를 요청한다. 폐기가 지연되는 동안에도 BATON 또는 ROUND의 최종 권한 검사가 접근을
막아야 한다. 대상 변경은 기존 링크를 수정하지 않고 새 의도로 생성한 뒤 이전 링크를
폐기한다.

생성 결과를 받기 전에 원본이 취소되면 호출자는 의도에 `CANCEL_REQUESTED` 삭제 표식을
남긴다. `CREATE_UNKNOWN`은 같은 키로 생성·재생을 수렴시켜 `linkId`를 얻은 즉시 단축
URL을 노출하기 전에 폐기한다. 확정적 `CREATE_REJECTED/NO_LINK`는 폐기 없이 취소를
완료한다. `CREATE_CONFLICT`와 `RECOVERY_REQUIRED`는 운영자가 원래 요청 내용과 존재 여부를
확정할 때까지 취소를 완료하지 않는다. 생성과 폐기 아웃박스는 의도별 순서를 보장한다.

## 7. 실패와 보상 의미

| 상황 | 계약된 결과 |
| --- | --- |
| 원본 커밋 뒤 GO 생성 실패 | 원본 트랜잭션을 롤백하지 않고 동일 의도를 재시도하며 링크 상태를 대기로 유지 |
| GO가 저장했지만 응답 유실 | 동일 키와 요청 내용 재생으로 같은 링크를 복구 |
| 생성 대기 중 원본 취소 | `CREATE_UNKNOWN`이면 `linkId` 회수 뒤 폐기, 확정적 `NO_LINK`면 즉시 완료, 충돌·복구 상태면 운영 확인 |
| GO 해석기 장애 | 링크 열기만 실패하며 BATON·ROUND의 직접 조회·편집은 계속 가능 |
| BATON·ROUND 장애 | GO는 대체 출처나 다른 대상으로 보내지 않음 |
| 원본 권한 회수 뒤 GO 폐기 지연 | 대상 서비스가 즉시 거부하고 GO 폐기는 비동기로 수렴 |
| 존재하지 않는 팀·시즌·방 | 대상 서비스가 자신의 안전한 찾을 수 없음·미승인 응답을 반환 |
| 봇·메신저의 선조회 | 도착 화면만 열 수 있고 권한·참여 허가를 소비하거나 발급하지 않음 |

GO는 해석할 때마다 BATON 또는 ROUND를 동기 조회하지 않는다. 대상 서비스도 자신의 일반
투영 조회에서 링크별로 GO를 호출하지 않는다.

## 8. 호환성과 기존 링크 정책

- `BATON`, `ROUND`, `NAVIGATION`, `MEETING_ENTRY`, `RESOURCE_OPEN` 문자열은 전송·DB 값으로
  동결한다. 이름 변경·삭제는 마이그레이션을 포함한 별도 호환성 파괴 변경이다.
- v1 경로 소유자는 아직 유효한 GO 링크가 있는 동안 정규 경로를 유지하거나, 새 링크
  발급과 이전 링크 폐기를 함께 배포한다.
- 계약 강화 전에 생성된 링크를 자동으로 기존 예외로 인정하지 않는다. 배포 전에 목록 조사해
  표의 정규 조합과 일치하는 링크만 유지하고 나머지는 폐기·재발급한다.
- 해석기가 DB의 계약 위반 대상을 발견하면 존재 여부를 드러내지 않도록
  `404 LINK_NOT_FOUND`로 응답하고 `Location`을 반환하지 않는다. 수동 DB 적재나 잘못된
  마이그레이션도 우회 경로가 되어서는 안 된다.
- GO 해석기는 저장된 열거형을 원문 문자열로 읽은 뒤 같은 정확한 정책을 적용한다. 따라서
  알려진 값의 비허용 조합뿐 아니라 알 수 없는 열거형도 `GET`과 `HEAD`에서 같은
  `404 LINK_NOT_FOUND`로 숨기고 리다이렉트하지 않는다.
- 저장 대상 계약 위반마다
  `baton.go.public.resolver.target.contract.violations` 지표를 증가시키고 `linkId`와 `requestId`만
  포함한 안전한 로그를 남긴다. 공개 코드, 대상 경로와 전체 단축 URL은 지표 태그나 로그에
  넣지 않는다.
- 새 대상 시스템, 목적 조합 또는 경로 서식은 PRD·ADR·도메인 정책·HTTP 계약 테스트와
  대상 서비스 E2E를 함께 변경해야 한다. 출처 설정만 추가해서 확장하지 않는다.

## 9. 현재 구현 상태와 운영 관문

| 항목 | 현재 상태 | 공개 전 필수 조치 |
| --- | --- | --- |
| GO 정확한 대상 계약 | 생성·해석, 안전한 목록 조사와 개별 정리 구현됨 | 배포 DB 전체 목록 조사를 실행하고 비허용 링크 폐기·재발급 증거 확정 |
| BATON 작업 공간 경로 | 구현됨 | v1은 기존 접근 키 보유 브라우저 복귀로만 노출 |
| BATON 신규 브라우저 진입 | 미구현 | 계정·초대·권한 수령 도착 화면 계약 전에는 지원 표시 금지 |
| ROUND 도착 화면·입장 전 확인 | 구현됨 | 정규 경로 E2E 고정 |
| BATON 모드 동일 출처 | GO 비로컬 설정은 동일 HTTPS 출처로 즉시 안전 차단, 경계는 미연결 | 전체 경계 경로와 쿠키·헤더 보존 검증 |
| ROUND BATON 모드 브라우저·시그널링 검증 | 구현됨 | BATON 세션·갱신 발급과 경계 라우팅 연결 |
| BATON 계정 세션·참여 허가 발급 | 미구현 | 5절의 세션, CSRF, 매핑, 참여자 역할, JWK와 쿠키 계약 구현 |
| BATON 방 매핑 | 미구현 | 일대일 활성 매핑, 영구 방 삭제 표식과 `study_id=teamId` 강제 |
| 경계 입장 보안 | 미연결 | 정확한 `Origin`, CORS 미사용, 쿠키 보존, 전달 헤더 정리, 경로 우선순위와 사전 인증 한도 검증 |
| 호출자 원격 생성·폐기 | 미구현 | 순서 보장 아웃박스, 멱등 작업자, 취소 삭제 표식과 생성→폐기 수렴 구현 |
| ROUND 시그널링 확장 | 단일 프로세스 상태 | 외부 상태 저장 전에는 단일 시그널링 복제본으로 운영 |

운영 API와 실행 안내서 구현만으로 운영 데이터 정리가 완료된 것은 아니다. 이 표의
미구현·미연결 항목과 배포 DB에서 실행·확정되지 않은 계약 전 데이터 목록 조사 때문에 현재
공개 운영 배포는 승인되지 않는다.

## 10. 계약 검증 시나리오

- 두 허용 조합의 정확한 정규 경로만 생성되고 재생 요청 내용 비교에도 같은 값이 사용된다.
- UUID 대문자, ROUND 금지 문자, 끝 슬래시, 추가 경로 구간과 표 밖 목적 조합은
  `400 INVALID_LINK`이며 DB에 쓰이지 않는다.
- 이전 형식 또는 수동 적재된 비허용 대상은 해석에서 `404 LINK_NOT_FOUND`이며
  리다이렉트되지 않는다.
- BATON 접근 키가 없는 신규 브라우저에 GO가 권한을 부여하거나 자격 증명을 노출하지
  않는다.
- ROUND 미리 가져오기만으로 참여 허가, TURN 자격 증명 또는 WebSocket 입장이
  발생하지 않는다.
- BATON·ROUND GO 출처가 다르거나 경계 경로 우선순위가 틀리면 배포 검증이 실패한다.
- 세션·CSRF 필수 제약이나 갱신 요청 본문·응답의 정확한 전송 형식과 다르면 안전하게 차단한다.
- 동일 방의 두 활성 매핑, 삭제 표식이 있는 방 재사용, `study_id != teamId` 또는 v1 `host`
  클레임 발급은 거부한다.
- 참여 허가 갱신 실패, 쿠키/JWT 만료 불일치, 미게시 `kid` 또는 방 클레임 불일치 때 TURN과
  시그널링 연결이 시작되지 않는다.
- 300초 이외 참여 허가 수명, 240초 이외 갱신 지연 또는 60초 미만 TURN 잔여 수명은
  배포·계약 테스트에서 거부한다.
- JWK 키 교체는 새 키 선게시와 이전 키의 360초 이상 중첩을 검증한다.
- 원격 생성 시간 초과 뒤 같은 의도 재생이 하나의 링크만 만들고, 원본 권한 회수는 대상
  거부와 GO 폐기로 수렴한다.
- 생성 결과를 모르는 상태에서 취소해도 `linkId` 회수 뒤 폐기되어 활성 고아 링크가
  남지 않는다.
