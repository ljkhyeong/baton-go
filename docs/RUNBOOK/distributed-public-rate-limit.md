# 공개 링크 분산 요청 제한

기본 구성에서는 인스턴스 안전장치만 실행한다. 복제본을 공개하기 전에 같은 Redis DB를
사용하는 공용 제한을 활성화한다. 이 기능의 한도는 전체 유입량 기준이며 사용자별 한도가 아니다.

| 환경 변수 | 의미 |
| --- | --- |
| `BATON_GO_DISTRIBUTED_RESOLVER_QUOTA_ENABLED` | 기본 `false`, 공용 제한 활성화 |
| `BATON_GO_REDIS_URI` | 활성화 시 필수, Lettuce Redis URI. 운영은 `rediss://` TLS 사용 |
| `BATON_GO_DISTRIBUTED_RESOLVER_QUOTA_CAPACITY` | 전체 창당 허용량, 기본 `300`, `1..1000000000` |
| `BATON_GO_DISTRIBUTED_RESOLVER_QUOTA_WINDOW` | 공용 창, 기본 `1m`, `1ms..1d` |
| `BATON_GO_DISTRIBUTED_RESOLVER_QUOTA_TIMEOUT` | 연결·명령 대기, 기본 `500ms`, `1ms..5s` |

Redis는 GO 환경 전용 DB와 자격 증명을 사용한다. ACL은 `PING`, 연결 초기화에 필요한
`CLIENT SETINFO`, `SELECT`, `EVAL`, `GET`, `PTTL`, `INCR`, `PEXPIRE`와 해당 키 접근을
운영 제품의 인증·프로토콜 초기화에 맞게 제한한다. `FLUSHDB`나 관리 명령은 애플리케이션에
부여하지 않는다. 실제 ACL·TLS 검증과 `noeviction`, 메모리·연결·고가용성 경보를 함께 준비한다.

URI에 자격 증명이 들어가므로 Secret이나 비밀값 관리자로 주입하고 로그·명령 인자·Git에
출력하지 않는다. Kubernetes 기본 구성은 선택적 `baton-go-redis-credentials` Secret의
`BATON_GO_REDIS_URI`를 읽는다. URI 이외의 값은 app-config에 설정한다. Compose는 별도
환경 파일의 같은 변수를 app 컨테이너에 전달한다. 비활성화 시 Redis 서버가 필요하지 않다.

모든 Pod의 대상 Redis DB·용량·창을 일치시킨다. 연결하지 못한 Pod는 시작하지 않으며 실행
중 Redis 실패는 공개 링크에 `503 RATE_LIMIT_UNAVAILABLE`를 반환한다. 이때 DB 링크 조회는
진행하지 않는다. `BatonGoDistributedResolverQuotaFailure` 경보로 연결·TLS·ACL·Redis 상태를
확인한다. 복구 후 카운터 허용량 안에서 공개 조회가 다시 진행되는지 검사한다.

배포 검증에서는 서로 다른 두 Pod로 보낸 요청의 합계가 공용 한도를 넘지 않는지 확인하고,
초과 요청의 `429`·`Retry-After`, Redis 차단 시 `503`, 관리 폐기 계속 사용을 확인한다.
Ingress의 `/api/v1` 비공개 분리와 `/l/{code}` 로그 마스킹은 별도로 검증한다.
실제 클러스터·Ingress 정보가 없는 상태의 로컬 테스트는 이 배포 증거를 대신하지 않는다.

```bash
./gradlew --no-daemon :bootstrap:redisTest
```

통합 검증은 고정 Redis 이미지로 동시성·TTL·연결 장애를 재현하며 운영 Redis를 사용하지 않는다.
[ADR-0013](../ADR/0013_distributed-public-resolver-quota/adr.md)에 실패 경계와 한계가 기록되어 있다.
