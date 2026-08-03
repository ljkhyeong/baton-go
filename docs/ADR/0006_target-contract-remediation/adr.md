# ADR-0006: 계약 전 링크는 승인된 inventory와 개별 폐기로 정리한다

- 상태: 채택
- 결정일: 2026-08-03

## 배경

PRD-0003의 v1 exact target 정책은 새 링크 생성과 공개 resolution에서 강제된다. 하지만
정책 도입 전에 저장됐거나 수동 SQL로 적재된 행은 여전히 DB와 backup에 남을 수 있다.
특히 알 수 없는 enum 문자열은 JPA entity hydration보다 먼저 실패하므로 일반 관리 조회와
폐기 경로만으로는 안전하게 조사하거나 정리할 수 없다.

resolver는 이런 행을 이미 `404 LINK_NOT_FOUND`로 숨기므로 무승인 자동 폐기가 필요한
긴급한 redirect 우회는 없다. 반면 폐기는 되돌리는 API가 없고 GO는 올바른 새 locator를
판단할 원본 aggregate를 소유하지 않는다.

## 결정

- 계약 전 데이터 정리는 기본 비활성화된 관리용 operations API로 수행한다.
- operations API는 기존 관리 Bearer 인증과 private ingress를 전제로 하며 maintenance
  window에만 명시적으로 활성화한다.
- `enabled`와 `private-ingress-confirmed`를 모두 true로 설정해야 controller가 등록된다. 확인
  flag는 public edge 차단 preflight evidence를 요구하는 이중 opt-in이지 network boundary의
  대체물이 아니다.
- inventory는 PK keyset pagination과 raw JDBC projection을 사용한다. raw target을
  application의 `TrustedTargetPolicy`로 판정해 SQL 정규식과 도메인 정책이 갈라지지 않게 한다.
- 응답과 운영 manifest에는 link ID, 계약 준수 여부, 폐기 상태, 생성 시각, 만료 시각,
  DB version과 생성 예약 존재 여부만 포함한다.
- target path, raw target system·purpose, code hash, short URL, idempotency hash와 그 digest는
  응답, 로그, 티켓과 CI artifact에 넣지 않는다. 비허용 target 전체를 잠재 credential로
  취급한다.
- 자동 bulk revoke는 제공하지 않는다. 운영자와 원본 domain owner가 승인한 link ID와
  expected version만 한 건씩 적용한다.
- 폐기 직전에 raw 행을 `SELECT ... FOR UPDATE`로 다시 읽고 exact 정책과 version을 검증한다.
  compliant 행은 거부하고, stale 행은 `409`로 보류하며, non-compliant 행만 최초 폐기 시각을
  보존해 멱등하게 폐기한다.
- inventory와 폐기는 enum entity hydration을 사용하지 않는다. 알 수 없는 enum도 같은
  절차로 판정하고 폐기할 수 있어야 한다.
- operations API 활성화와 직접 DB writer 중지는 inventory 시작부터 최종 재스캔까지 하나의
  maintenance window로 관리한다.

## 재발급 소유권

GO는 legacy target을 trim·parse·교정해 새 링크를 만들지 않는다. BATON 또는 ROUND의 원본
aggregate 소유자가 authoritative mapping을 확인하고 새 canonical UUID intent로 정상 생성
API를 호출한다. 새 링크의 저장과 게시를 확인한 뒤 이전 링크를 폐기한다.

원본 owner나 canonical mapping을 확정할 수 없는 행은 추측해서 재발급하지 않는다. 승인
manifest에서 `HOLD`로 남기고 공개 rollout gate도 닫지 않는다.

## 운영 안전 조건

- 정리 전에 DB backup과 해당 HMAC secret-manager version을 하나의 복구 단위로 보존한다.
- exact resolver 이전 binary로 rollback하지 않는다. rollback은 정리한 legacy link를 다시
  redirect 가능한 상태로 만들 수 있다.
- 외부 네트워크 probe에서 operations와 다른 `/api/v1` 관리 경로가 BATON GO의 Bearer
  challenge까지 도달하지 않는지 확인하고 그 결과를 release evidence에 남긴다.
- 비허용 target에 access key, session 또는 grant가 포함됐을 가능성이 있으면 폐기와 별도로
  해당 credential 소유 서비스에서 회전하고 backup 보존 범위를 검토한다.
- 전체 재스캔에서 미폐기 non-compliant 행과 승인되지 않은 `HOLD`가 모두 0일 때만 이 gate를
  통과한 것으로 기록한다.

## 결과

장점:

- unknown enum과 비허용 path를 관리 응답에 노출하지 않고 조사·폐기할 수 있다.
- 도메인 exact 정책을 inventory와 resolution이 공유해 정책 drift를 줄인다.
- version과 row lock으로 inventory 이후 변경된 행을 무심코 폐기하지 않는다.
- 재발급 권한과 원본 상태를 BATON·ROUND에 남겨 서비스 경계를 지킨다.

비용:

- 최초 production 정리에는 운영자와 각 domain owner의 승인 작업이 필요하다.
- bulk 자동화보다 느리지만 되돌릴 수 없는 오폐기와 잘못된 재발급을 피한다.
- maintenance API의 배포만으로 gate가 닫히지 않는다. 실제 배포 DB 전체 스캔과 정리 증거가
  별도로 필요하다.
