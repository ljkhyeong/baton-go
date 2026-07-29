# PRD-0001: BATON GO 제품 기준선

- 상태: 초기 기준선
- 작성일: 2026-07-29

## 1. 제품 정의

BATON GO는 조직의 역할, 운영 회차와 실시간 회의로 들어가는 안정적인 링크 진입점이다.
주소 문자열을 짧게 만드는 데 그치지 않고 링크의 활성 시간, 만료, 폐기와 대상 시스템을
명시적으로 관리한다.

## 2. 해결하려는 문제

- 역할 담당자나 운영 회차가 바뀔 때 공유된 링크가 오래된 목적지를 가리킨다.
- 긴 workspace·회의 주소를 다시 전달하는 과정에서 접근 비밀까지 복제하기 쉽다.
- ROUND room ID가 방 위치와 입장 권한을 동시에 암시한다.
- 여러 제품이 각자 링크 코드, 만료와 감사 규칙을 중복 구현할 수 있다.

## 3. 제품 원칙

### 링크는 위치이며 최종 권한이 아니다

GO는 신뢰 대상과 정책을 해석하지만 BATON workspace 권한이나 ROUND room 입장을 대신
허가하지 않는다.

### 비밀을 목적지에 저장하지 않는다

BATON access key, 세션, Bearer token과 ROUND join ticket을 대상 경로에 넣지 않는다.

### 공개 GET은 안전하다

메신저와 검색 봇이 링크를 미리 열 수 있으므로 `GET`과 `HEAD`는 일회용 권한을 소비하거나
승인 상태를 바꾸지 않는다.

### 시간은 경계값까지 계약이다

`notBefore`는 해당 시각부터 허용하고 `expiresAt`은 해당 시각부터 만료로 본다. 모든 판정은
주입된 `Clock`과 UTC `Instant`를 사용한다.

## 4. 핵심 개념

| 개념 | 의미 |
| --- | --- |
| Smart Link | 코드, 대상, 목적, 활성 기간과 폐기 상태를 가진 링크 |
| Target System | 승인된 목적지 시스템. 첫 버전은 `BATON`, `ROUND` |
| Target Path | 대상 시스템 base URL 아래의 안전한 절대 경로 |
| Purpose | `NAVIGATION`, `MEETING_ENTRY`, `RESOURCE_OPEN` 중 링크의 의도 |
| Resolution | 공개 코드를 찾아 상태를 확인하고 신뢰 목적지로 안내하는 읽기 작업 |
| Redemption | 인증 후 일회용 권한을 소비하는 향후 작업. Resolution과 분리한다. |

## 5. 첫 세로 흐름

1. 신뢰된 관리 호출자가 대상 시스템, 경로, 목적과 선택적 활성 기간으로 링크를 만든다.
2. 서버는 128-bit 이상 CSPRNG 코드를 한 번 반환하고 SHA-256 해시만 저장한다.
3. 사용자가 `/l/{code}`를 열면 서버는 현재 시각에 활성인지 확인한다.
4. 활성 링크는 신뢰된 base URL과 상대 경로를 결합해 리다이렉트한다.
5. 만료되거나 폐기된 링크는 안정적인 오류로 거부한다.
6. 관리 호출자는 링크를 즉시 폐기할 수 있다.

## 6. BATON·ROUND 통합 경계

- BATON `RoleResource` 원본 목적지는 BATON이 계속 소유한다.
- BATON은 workspace 조회나 자료 저장 transaction에서 GO를 호출하지 않는다.
- 클릭 시점 use case가 현재 workspace 접근과 자료 소유권을 먼저 검증한다.
- GO 장애는 링크 열기에만 영향을 주며 BATON 조회·편집을 막지 않는다.
- ROUND 연동 전에는 room ID를 locator로만 취급한다.
- 실제 meeting join은 BATON 신원·멤버십 확인과 ROUND one-time ticket 계약이 생긴 뒤 추가한다.

## 7. 비범위

- 사용자 계정, 조직 멤버십과 역할별 권한
- URL에 담긴 bearer credential의 축약
- 임의 외부 URL과 open redirect
- 광고·사용자 추적용 상세 클릭 분석
- 녹화·미디어·WebRTC signaling 처리
