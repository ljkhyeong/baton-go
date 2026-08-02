# HANDOFF

- BATON GO의 정책형 링크 생성·해석·폐기와 원격 생성 idempotency를 완료했다.
- 멱등 재생 시 현재 HMAC 파생 결과를 저장된 코드 해시와 대조하며, 파생 비밀 변경으로
  기존 short URL을 재생할 수 없으면 잘못된 URL 대신
  `500 LINK_CODE_REPLAY_UNAVAILABLE`로 실패한다.
- 링크 코드 HMAC 파생 version·fingerprint를 `link_code_key_guard` singleton에 결합한다.
  시작 시점과 생성 예약 전에 검증하며, 링크와 예약이 모두 빈 DB만 자동 결합한다. 기존
  데이터가 있는 미결합 DB나 다른 identity는 secret·fingerprint를 노출하지 않고 fail-closed
  한다. 재생 code hash 검증은 방어 계층으로 유지한다.
- DB backup과 해당 HMAC secret-manager version은 하나의 복구 단위다. 기존 데이터가 있는
  DB에 guard를 처음 도입할 때는 writer를 중지하고 기존 secret 및 canary를 검증한 뒤
  singleton을 결합한다. key ring 전에는 secret을 회전하지 않는다.
- MySQL Testcontainers가 동시 동일 요청을 링크·예약 각 한 건으로 직렬화하고, 최초 owner
  rollback 때 발생할 수 있는 deadlock victim도 동일 키 재시도로 복구되는지 검증한다.
- 관리 Bearer 인증은 scheme 대소문자를 구분하지 않으며, `401`에는
  `WWW-Authenticate: Bearer realm="baton-go-management"`를 반환한다. 생성·재생 응답은
  `Cache-Control: no-store`다.
- 공개 resolver는 DB 조회 전에 인스턴스 aggregate rate-limit backstop을 적용한다.
  client IP와 전달 헤더를 신뢰하지 않으며, 다중 replica 합산 제한은 ingress가 소유한다.
- PRD-0003과 ADR-0005에서 v1 교차 서비스 target을 typed locator로 확정했다. 허용 조합은
  `BATON + NAVIGATION + /teams/{teamId}/seasons/{seasonId}`와
  `ROUND + MEETING_ENTRY + /room/{roomId}`뿐이며 `RESOURCE_OPEN`은 예약 상태다.
- BATON locator는 기존 access key를 이미 보유한 브라우저의 workspace 복귀만 지원한다.
  신규 브라우저 초대·권한 부여는 BATON account/claim landing 전까지 지원하지 않는다.
- ROUND의 현재 권한 모델은 one-time ticket이 아니라 BATON session·CSRF 뒤 HttpOnly cookie로
  갱신하는 짧은 수명의 participation grant다. ROUND browser/signaling 쪽 검증은 있으나
  BATON session·grant 발급 endpoint와 edge routing은 아직 연결되지 않았다.
- BATON mode에서 GO의 BATON·ROUND target origin은 같은 BATON public HTTPS origin이어야 한다.
  BATON은 room별 one-to-one active resource mapping과 영구 tombstone을 소유하며 v1 grant는
  `study_id=teamId`, `role=participant`로 제한한다.
- 다음 우선순위는 PRD-0003의 exact target 조합을 생성·resolution 양쪽에서 fail-closed로
  강제하고, 계약 전 fixture·데이터를 정리하는 것이다. 이후 REST Docs/OpenAPI,
  edge/distributed rate limit, access-log 코드 마스킹과 운영 배포를 연결한다.
- BATON과 ROUND 저장소에는 이번에도 통합 코드를 추가하지 않았다. 두 저장소는 실제 route와
  권한 경계를 읽기 전용으로 확인했다.
- 계정 기반 사용자·역할 권한은 BATON P3 identity 이전에 구현된 것으로 주장하지 않는다.
