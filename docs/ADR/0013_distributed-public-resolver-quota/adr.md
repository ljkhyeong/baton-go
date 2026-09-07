# ADR-0013: 공개 링크의 공용 요청 제한

- 상태: 채택
- 결정일: 2026-09-05

## 결정

여러 GO 인스턴스가 같은 Redis DB의 `baton-go:public-resolver:quota:v1` 카운터를 공유한다.
기존 인스턴스 안전장치를 통과한 공개 `GET·HEAD /l/{code}`만 Redis를 확인하고, 한도를 통과한
요청만 MySQL 링크 조회로 진행한다. 관리 API와 다른 경로는 영향을 받지 않는다.

개별 링크 코드·IP·전달 헤더를 키로 사용하지 않는다. 이 기능은 전체 DB 유입량 보호이며
클라이언트별 공정한 배분이나 네트워크 DDoS 방어를 제공하지 않는다. 해당 보호는 실제 공개
프록시 경계에서 별도로 적용한다. 공개 접근 로그의 코드 가림도 프록시 경계의 책임이다.

Redis Lua 한 번으로 카운터 읽기·허용·증가·TTL 설정을 원자적으로 수행한다.
첫 요청부터 정해진 시간 동안 요청 수를 집계한다. 구간이 바뀌는 시점에는 두 구간의
허용량이 연속 적용될 수 있다.
거절한 요청은 카운터와 만료를 연장하지 않는다. 재시도 대기 시간은 Redis의 남은 TTL을
초 단위로 올림하여 기존 `429`의 `Retry-After`에 넣는다. 서버별 시계 차이는 구간 계산에 쓰지 않는다.

Lettuce의 공유 연결과 표준 URI·TLS·재연결을 사용한다. 연결과 명령 대기 시간을 제한하고
끊긴 연결의 명령을 대기열에 누적하지 않는다. Redis 실패, 잘못된 카운터나 TTL 없는 카운터는
`503 RATE_LIMIT_UNAVAILABLE`로 닫는다. HTML과 JSON을 기존 공개 오류 형식으로 표시하며
장애 횟수만 집계한다. Redis 준비 실패는 애플리케이션 시작을 실패시킨다. 실행 중 Redis 장애는
공개 요청을 막지만 관리 폐기와 상태 확인은 계속 사용할 수 있다.

기능은 기본 중지한다. 운영 Redis의 TLS·ACL·고가용성·메모리 정책을 준비한 뒤 모든 Pod를
같은 Redis DB·용량·시간 구간 설정으로 활성화한다. Redis DB는 환경별로 분리한다.
Redis 키의 만료 전 삭제나 failover 중 미복제 데이터 유실은 일시적으로 허용량을 늘릴 수 있다.
Redis는 `noeviction`과 운영 고가용성 정책을 사용하고 기존 로컬 한도도 유지한다. 절대적인 과금·사용권
차감에 이 카운터를 재사용하지 않는다.

## 검증 근거와 절차

두 Redis 연결의 동시 요청 합계, 구간 만료, TTL 훼손과 연결 종료를 통합 테스트한다.
Spring 활성화 조립과 공개 JSON·HTML·HEAD 오류, DB 조회 미실행도 검증한다.
[운영 절차](../../RUNBOOK/distributed-public-rate-limit.md)를 따른다.

- [Redis INCR의 원자적 요청 제한 예시](https://redis.io/docs/latest/commands/incr/)
- [Lettuce 연결·대기열·시간 제한](https://redis.github.io/lettuce/advanced-usage/client-options/)
