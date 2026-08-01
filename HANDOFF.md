# HANDOFF

- BATON GO의 정책형 링크 생성·해석·폐기와 원격 생성 idempotency를 완료했다.
- 멱등 재생 시 현재 HMAC 파생 결과를 저장된 코드 해시와 대조하며, 파생 비밀 변경으로
  기존 short URL을 재생할 수 없으면 잘못된 URL 대신
  `500 LINK_CODE_REPLAY_UNAVAILABLE`로 실패한다.
- MySQL Testcontainers가 동시 동일 요청을 링크·예약 각 한 건으로 직렬화하고, 최초 owner
  rollback 때 발생할 수 있는 deadlock victim도 동일 키 재시도로 복구되는지 검증한다.
- 관리 Bearer 인증은 scheme 대소문자를 구분하지 않으며, `401`에는
  `WWW-Authenticate: Bearer realm="baton-go-management"`를 반환한다. 생성·재생 응답은
  `Cache-Control: no-store`다.
- 다음 우선순위는 대상 시스템별 허용 경로 template/structured locator 확정,
  REST Docs/OpenAPI, public resolver rate limit과 운영 배포 연결이다.
- BATON과 ROUND 저장소에는 아직 통합 코드를 추가하지 않았다. 먼저 BATON의 클릭 시점
  use case와 ROUND의 one-time join ticket 계약을 각각 확정해야 한다.
- 계정 기반 사용자·역할 권한은 BATON P3 identity 이전에 구현된 것으로 주장하지 않는다.
