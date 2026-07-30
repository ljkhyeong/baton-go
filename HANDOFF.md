# HANDOFF

- BATON GO의 정책형 링크 생성·해석·폐기와 원격 생성 idempotency를 완료했다.
- MySQL Testcontainers가 동시 동일 요청을 링크·예약 각 한 건으로 직렬화하는지 검증한다.
- 다음 우선순위는 REST Docs/OpenAPI, public resolver rate limit과 운영 배포 연결이다.
- BATON과 ROUND 저장소에는 아직 통합 코드를 추가하지 않았다. 먼저 BATON의 클릭 시점
  use case와 ROUND의 one-time join ticket 계약을 각각 확정해야 한다.
- 계정 기반 사용자·역할 권한은 BATON P3 identity 이전에 구현된 것으로 주장하지 않는다.
