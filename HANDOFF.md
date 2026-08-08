# HANDOFF

- BATON GO의 정책형 링크 생성·해석·폐기와 원격 생성 idempotency를 완료했다.
- 멱등 재생 시 현재 HMAC 파생 결과를 저장된 코드 해시와 대조하며, 파생 비밀 변경으로
  기존 short URL을 재생할 수 없으면 잘못된 URL 대신
  `500 LINK_CODE_REPLAY_UNAVAILABLE`로 실패한다.
- 신규 생성은 lowercase UUID v1..5/RFC variant와 Java/JDBC 안전 저장 범위의 마이크로초 payload만
  허용한다. 과거 배포가 허용했던 canonical 대문자·기타 UUID와 범위 안 나노초 payload는
  동일 키의 기존 예약과 저장 payload가 일치할 때만 replay-only로 처리한다. 기존 예약이
  없으면 안정된 `400`이며 예약·링크 행을 새로 만들지 않고, 범위 밖 시각은 항상 거부한다.
- 링크 코드 HMAC 파생 version·fingerprint를 `link_code_key_guard` singleton에 결합한다.
  시작 시점과 생성 예약 전에 검증하며, 링크와 예약이 모두 빈 DB만 자동 결합한다. 기존
  데이터가 있는 미결합 DB나 다른 identity는 secret·fingerprint를 노출하지 않고 fail-closed
  한다. 재생 code hash 검증은 방어 계층으로 유지한다.
- DB backup과 해당 HMAC secret-manager version은 하나의 복구 단위다. 기존 데이터가 있는
  DB에 guard를 처음 도입할 때는 writer를 중지하고 기존 secret 및 canary를 검증한 뒤
  전용 `guard-tool` 모듈의 `baton-go-guard-binding.jar`로 singleton을 결합한다. 도구는
  canary를 stdin으로만 받고 예약·링크 hash 일치를 확인한 뒤 한 transaction에서 결합한다.
  서버의 웹 서버·Hibernate/Spring Data JPA·Actuator runtime과 분리되어 있으며 key ring
  전에는 secret을 회전하지 않는다.
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
- GO는 이 exact 조합을 생성과 resolution 양쪽에서 강제한다. 알려진 값으로 만든 비허용 생성은
  링크와 예약을 쓰기 전에 `400 INVALID_LINK`로 거부한다. resolver는 raw 문자열 projection으로
  저장 target을 읽어 비허용 조합과 알 수 없는 enum 모두 `GET·HEAD`에서 Location 없는
  `404 LINK_NOT_FOUND`로 숨긴다.
- 저장 target 계약 위반은
  `baton.go.public.resolver.target.contract.violations` metric으로 집계하고 linkId와 requestId만
  포함한 안전한 로그를 남긴다. 공개 코드, target path와 전체 short URL은 기록하지 않는다.
- BATON locator는 기존 access key를 이미 보유한 브라우저의 workspace 복귀만 지원한다.
  신규 브라우저 초대·권한 부여는 BATON account/claim landing 전까지 지원하지 않는다.
- ROUND의 현재 권한 모델은 one-time ticket이 아니라 BATON session·CSRF 뒤 HttpOnly cookie로
  갱신하는 짧은 수명의 participation grant다. ROUND browser/signaling 쪽 검증은 있으나
  BATON session·grant 발급 endpoint와 edge routing은 아직 연결되지 않았다.
- BATON mode에서 GO의 BATON·ROUND target origin은 같은 BATON public HTTPS origin이어야 한다.
  두 target이 모두 loopback인 로컬 개발만 서로 다른 HTTP port를 허용하고, 그 외 설정은
  같은 HTTPS origin이 아니면 시작 단계에서 거부한다.
  loopback은 `localhost`, 선행 0이 없는 canonical dotted-decimal IPv4 `127.0.0.0/8`과
  IPv6 loopback literal로 판정하고 명시적 origin port는 `1..65535`만 허용한다.
  BATON은 room별 one-to-one active resource mapping과 영구 tombstone을 소유하며 v1 grant는
  `study_id=teamId`, `role=participant`로 제한한다.
- 공개 production rollout은 아직 승인되지 않았다. 다음 우선순위는 계약 전 저장 데이터를
  배포 DB에서 inventory해 비허용 링크를 폐기·재발급하고, PRD-0003의 나머지 gate를 연결하는
  것이다. 이후 REST Docs/OpenAPI, edge/distributed rate limit, access-log 코드 마스킹과 운영
  배포를 연결한다.
- 계약 전 데이터 정리는 ADR-0006의 승인형 흐름을 따른다. 기본 비활성화된 operations API는
  raw target을 응답하지 않고 모든 행의 compliance, 폐기 상태, 예약 존재 여부와 DB version만
  keyset pagination으로 제공한다. 자동 bulk revoke는 없으며 승인된 non-compliant link ID만
  raw row lock과 version 재검증 뒤 멱등 폐기한다.
- operations는 enable과 private-ingress-confirmed가 모두 true일 때만 등록한다. 두 번째 flag는
  public edge 차단 preflight evidence 뒤에만 설정하며 실제 network boundary를 대신하지 않는다.
- GO는 legacy target을 교정해 재발급하지 않는다. 원본 BATON/ROUND owner가 authoritative
  mapping과 새 intent UUID로 정상 생성한 뒤 이전 링크를 폐기한다. 실제 배포 DB 전체 재스캔과
  `unrevoked non-compliant=0`, 미승인 `HOLD=0` 증거 전에는 inventory gate를 완료 처리하지 않는다.
- BATON과 ROUND 저장소에는 이번에도 통합 코드를 추가하지 않았다. 두 저장소는 실제 route와
  권한 경계를 읽기 전용으로 확인했다.
- 계정 기반 사용자·역할 권한은 BATON P3 identity 이전에 구현된 것으로 주장하지 않는다.
