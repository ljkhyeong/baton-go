# HANDOFF

- BATON GO의 첫 세로 흐름인 정책형 링크 생성·해석·폐기와 MySQL/Flyway 런타임 스모크를 완료했다.
- 다음 우선순위는 원격 생성 idempotency, REST Docs/OpenAPI, MySQL Testcontainers와 rate limit이다.
- BATON과 ROUND 저장소에는 아직 통합 코드를 추가하지 않았다. 먼저 BATON의 클릭 시점
  use case와 ROUND의 one-time join ticket 계약을 각각 확정해야 한다.
- 계정 기반 사용자·역할 권한은 BATON P3 identity 이전에 구현된 것으로 주장하지 않는다.
