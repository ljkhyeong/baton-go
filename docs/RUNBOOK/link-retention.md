# 링크 보존 기간 설정

기본값은 `BATON_GO_LINK_RETENTION_ENABLED=false`다. 운영자가 재시도 시 기존 URL 반환 보장 기간, 백업·감사
보존과 복구 요구를 확인하고 `BATON_GO_LINK_RETENTION_PERIOD`를 정하기 전에는 켜지 않는다.
이 구현은 운영 보존 기간을 임의로 확정하지 않는다.

| 환경 변수 | 의미 |
| --- | --- |
| `BATON_GO_LINK_RETENTION_ENABLED` | 자동 정리 여부, 기본 `false` |
| `BATON_GO_LINK_RETENTION_PERIOD` | 종료 후 보존 기간, 활성화 시 필수. `90d` 같은 Spring Duration 형식 |
| `BATON_GO_LINK_RETENTION_BATCH_SIZE` | 실행당 처리량, 기본 `100`, 범위 `1..500` |
| `BATON_GO_LINK_RETENTION_INTERVAL` | 이전 실행 완료 후 간격과 최초 실행 지연, 기본 `60s` |

V7 적용과 전체 Pod 교체, DB·키 묶음 복구본을 확인한다. 정리 시점은 Pod에 주입된 UTC Clock을
사용하므로 서버 시계 동기화도 확인한다. 다음 조회로 선택한 기준 시각 이전 행 수를 조사하되,
운영 데이터의 경로·코드·생성 키를 출력하지 않는다.

```sql
SELECT COUNT(*)
FROM smart_links s JOIN link_creation_requests r ON r.link_id = s.id
WHERE s.retired_at <= :approved_cutoff AND r.purged_at IS NULL;
```

승인한 기간과 처리량을 배포 설정에 넣어 활성화한다. `baton_go_link_retention_purged_total`의
증가와 `baton_go_link_retention_failures_total`을 관찰한다. `BatonGoLinkRetentionFailure`가
발생하면 DB 상태와 정리 예외 종류를 확인한다. 기능을 끄면 후속 실행이 중단되며 이미 정리한
링크가 복원되지는 않는다. 삭제된 링크의 동일 요청은 `410 LINK_PURGED`이고 새 요청 키가
있어야 새 링크를 만들 수 있다. 상세 계약은 [ADR-0012](../ADR/0012_link-retention/adr.md)다.

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
