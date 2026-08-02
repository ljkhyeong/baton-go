# ADR-0005: 교차 서비스 target을 typed locator로 제한

- 상태: 채택
- 결정일: 2026-08-02

## 배경

ADR-0002는 `targetSystem`과 문법적으로 안전한 path를 분리해 open redirect와 URL credential을
막았다. 그러나 안전한 문자만 검사하면 `BATON + MEETING_ENTRY`, 임의 `/roles/...` 또는
credential처럼 보이는 임의 segment도 저장할 수 있다. 이는 대상이 신뢰 origin이라는 사실만
보장할 뿐 승인된 업무 진입점이라는 사실은 보장하지 못한다.

실제 서비스 route도 초기 예시와 달랐다.

- BATON workspace route는 `/teams/{teamId}/seasons/{seasonId}`이며 신규 브라우저 권한에는
  별도 access key가 필요하다.
- ROUND entry route는 `/room/{roomId}`이며 room ID는 제한된 lowercase `4-4-4` 형식이다.
- ROUND 입장은 공개 GET이 아니라 BATON session·CSRF 뒤 발급되는 짧은 수명의 participation
  grant로 통제한다. 현재 grant는 one-time ticket이 아니다.

## 결정

- target을 임의 안전 path가 아니라 `(TargetSystem, LinkPurpose, canonical locator)`의
  합으로 취급한다.
- v1은 다음 두 조합만 허용한다.
  - `BATON + NAVIGATION + /teams/{canonical UUID}/seasons/{canonical UUID}`
  - `ROUND + MEETING_ENTRY + /room/{canonical ROUND room ID}`
- `RESOURCE_OPEN`과 그 밖의 조합은 안정적인 landing 계약이 생길 때까지 거부한다.
- producer와 GO는 canonical locator를 그대로 사용하고 whitespace, case, trailing slash,
  percent encoding 또는 추가 segment를 자동 교정하지 않는다.
- 생성 시뿐 아니라 resolution 시에도 같은 정책을 확인해 수동 적재와 legacy 데이터가
  redirect allowlist를 우회하지 못하게 한다.
- BATON locator는 기존 권한 브라우저의 복귀만 뜻한다. access key가 없는 신규 브라우저
  초대는 별도 인증·claim 계약 전까지 지원하지 않는다.
- ROUND locator는 pre-join을 시작할 뿐 입장 권한을 나타내지 않는다. participation grant,
  session, CSRF와 TURN credential은 GO를 거치지 않는다.
- BATON mode의 `BATON`·`ROUND` trusted origin은 같은 BATON public HTTPS origin으로 고정하고,
  edge가 논리 target에 따라 landing, asset, refresh, signaling과 TURN 경로를 분리한다.
- BATON은 한 room을 하나의 active team·season·resource mapping에만 연결하고 종료한 room ID를
  재사용하지 않는다. v1 grant는 `study_id=teamId`, `role=participant`로 제한한다.
- 생성 호출자는 원본 aggregate commit 뒤 안정적인 intent UUID로 GO를 호출하고, 실패와
  폐기는 ordered outbox와 취소 tombstone 또는 동등한 재시도 흐름으로 수렴시킨다.

정확한 wire 형식, same-origin endpoint, cookie·grant 경계와 rollout gate는 PRD-0003을
규범 계약으로 삼는다.

## 결과

장점:

- 신뢰 origin 안의 임의 route를 open redirect처럼 악용하는 범위를 줄인다.
- purpose가 단순 metadata가 아니라 허용 locator와 함께 검증되는 업무 계약이 된다.
- access key나 grant를 임의 path segment로 숨겨 저장하는 우회를 차단한다.
- 공개 GET과 최종 authorization을 분리해 bot prefetch가 권한을 발급하거나 소비하지 않는다.

비용:

- 현재 일반 `TargetPath` 검증을 target별 domain policy로 강화해야 한다.
- 기존 fixture와 계약 전 데이터는 inventory 후 수정·폐기·재발급해야 한다.
- BATON route 또는 ROUND room grammar 변경은 GO와 조율하는 breaking change가 된다.
- BATON 계정 session과 participation-grant 발급이 연결되기 전에는 ROUND 공개 rollout을 할
  수 없다.
- 같은 public origin의 edge routing과 room mapping·tombstone 상태를 BATON에서 추가로
  운영해야 한다.

## 대안 검토

문법적으로 안전한 임의 path를 계속 허용하는 안은 대상 서비스의 새 route가 자동 지원된다는
장점이 있다. 하지만 purpose 조합과 credential-free locator를 검증할 수 없고, route 변경이
GO의 보안 경계를 암묵적으로 넓히므로 채택하지 않았다.

GO가 resolution마다 BATON·ROUND를 동기 조회하는 안도 채택하지 않았다. 대상 상태를 복제하거나
공개 resolver의 가용성을 세 서비스에 결합하기 때문이다. 최종 권한은 redirect 뒤 대상
서비스가 자체 상태로 판단한다.
