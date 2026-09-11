# 링크 보존 기간 설정

기본값은 `BATON_GO_LINK_RETENTION_ENABLED=false`다. 운영자가 재시도 시 기존 URL 반환 보장 기간, 백업·감사
보존과 복구 요구를 확인하고 `BATON_GO_LINK_RETENTION_PERIOD`를 정하기 전에는 켜지 않는다.
이 구현은 운영 보존 기간을 임의로 확정하지 않는다.

| 환경 변수 | 의미 |
| --- | --- |
| `BATON_GO_LINK_RETENTION_ENABLED` | 자동 정리 여부, 기본 `false` |
| `BATON_GO_LINK_RETENTION_PERIOD` | 종료 후 보존 기간, 활성화 시 필수. `90d` 같은 Spring Duration 형식 |
| `BATON_GO_LINK_RETENTION_BATCH_SIZE` | 실행당 정리 건수, 기본 `100`, 범위 `1..500` |
| `BATON_GO_LINK_RETENTION_INTERVAL` | 이전 실행 완료 후 간격과 최초 실행 지연, 기본 `60s`, 최소 `1s` |

V7 적용과 전체 Pod 교체, DB·키 묶음 복구본을 확인한다. 정리 시점은 Pod에 주입된 UTC Clock을
사용하므로 서버 시계 동기화도 확인한다. 다음 조회로 선택한 기준 시각 이전 행 수를 조사하되,
운영 데이터의 경로·코드·생성 키를 출력하지 않는다.

```sql
SELECT COUNT(*)
FROM smart_links s JOIN link_creation_requests r ON r.link_id = s.id
WHERE s.retired_at <= :approved_cutoff AND r.purged_at IS NULL;
```

승인한 기간과 실행당 정리 건수를 배포 설정에 넣어 활성화한다. 다음 지표와 경보를 함께 확인한다.

- `baton_go_link_retention_purged_total`: 정리한 링크 수
- `baton_go_link_retention_failures_total`: 실패한 실행 수. 증가하면 `BatonGoLinkRetentionFailure` 발생
- `baton_go_link_retention_scheduler_heartbeat_seconds`: 스케줄러 시작 또는 최근 실행 완료 시각
- `baton_go_link_retention_scheduler_interval_seconds`: 설정한 실행 간격

완료 시각이 실행 간격의 3배(최소 3분)를 넘긴 상태로 2분 지속되면
`BatonGoLinkRetentionSchedulerStalled`가 발생한다. 실패한 실행도 완료 시각은 갱신하므로 실패 원인은
실패 경보에서 확인한다. 정리 호출이 끝나지 않거나 스케줄러가 실행되지 않을 때는 정체 경보에서
스케줄러 스레드와 DB 쿼리 실행 시간을 확인한다. 자동 정리를 끄면 지표와 경보가 생성되지 않는다.

기능을 끄면 후속 실행이 중단되며 이미 정리한 링크가 복원되지는 않는다. 삭제된 링크의 동일 요청은
`410 LINK_PURGED`이고 새 요청 키가 있어야 새 링크를 만들 수 있다. 상세 계약은
[ADR-0012](../ADR/0012_link-retention/adr.md)다. 실행 간격이 비어 있거나 1초보다 짧으면
애플리케이션 시작 단계에서 거부한다.

이전 HMAC 키를 제거하기 전에는 해당 키로 링크를 발급하는 Pod가 모두 종료됐는지 확인한다.
기존 URL 복원에 필요한 키는 다음 집계로 확인하며 키 해시나 지문은 출력하지 않는다.

```sql
SELECT key_id, COUNT(*) AS replay_required
FROM link_creation_requests
WHERE purged_at IS NULL
GROUP BY key_id;
```

자동 삭제 표시가 있는 생성 예약과 백업·감사 로그는 별도 보존 대상이다. 이 기능만으로 개인정보 보존 정책이나
PVC 용량 경보·증설 정책이 완료되었다고 판단하지 않는다.
