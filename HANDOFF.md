# 인수인계

## 현재 기준

- BATON GO의 링크 생성·재생·조회·공개 해석·폐기와 v1 신뢰 대상 정책은 구현되어 있다.
  제품 동작은 [제품 기준선](docs/PRD/0001_product-baseline/spec.md), HTTP 동작은
  [API 계약](docs/PRD/0002_api-contract/spec.md)을 정본으로 삼는다.
- BATON·ROUND 위치 식별자와 최종 권한 경계는
  [교차 서비스 링크 계약](docs/PRD/0003_cross-service-link-contract/spec.md)을 따른다.
  GO 링크는 위치만 제공하며 BATON 접근 권한이나 ROUND 입장 권한을 부여하지 않는다.
- 링크 코드 HMAC 보호 장치, 멱등 재생의 공개 출처 보존과 MySQL 절대 시각 저장 결정은
  각각 [ADR-0004](docs/ADR/0004_link-code-key-binding/adr.md),
  [ADR-0009](docs/ADR/0009_idempotent-public-origin-replay/adr.md),
  [ADR-0007](docs/ADR/0007_mysql-instant-storage/adr.md)을 따른다.
- 계약 전 저장 데이터 조사·폐기 API는 구현되어 있지만 기본 비활성화 상태다. 공개 경계 차단을
  확인한 유지 보수 시간에만 두 활성화 값을 함께 사용하며, 일반 운영에서는 모두 `false`로 둔다.
- GO 전용 MySQL과 비공개 Kubernetes 기본 구성은 준비되어 있다. 이는 배포 기반일 뿐 실제
  클러스터 검증이나 공개 운영 승인 증거가 아니다.

## 공개 운영 전 남은 관문

1. 배포 DB 전체를 [대상 계약 v1 정리 실행서](docs/RUNBOOK/target-contract-v1-remediation.md)로
   조사하고 `unrevoked non-compliant=0`, 미승인 `HOLD=0` 증거를 확보한다.
2. BATON 세션·CSRF·참여 허가, 권위 있는 회의실 매핑과 종료 표식, 호출자
   outbox·취소 표식을 PRD-0003의 소유 서비스에 연결한다. BATON과 ROUND 저장소에는 아직 이
   통합을 완료한 코드가 없다고 본다.
3. 공개 `/l`과 비공개 `/api/v1` 외부 경계를 분리하고, 분산 요청률 제한과
   `/l/{code}` 접근 로그 마스킹을 적용한다.
4. 실제 비공개 클러스터에서 DNS·TLS·CNI·NetworkPolicy·startup/liveness/readiness probe,
   PVC와 Secret 수명주기를 검증한다.
5. DB와 같은 버전의 `BATON_GO_LINK_CODE_SECRET`을 한 복구 단위로 사용해 백업·복원,
   장애 대응과 이미지 되돌리기 훈련을 완료한다.
6. 외부 소비 계약을 고정할 REST Docs/OpenAPI 산출물을 연결한다.

## 운영 안전선

- 기존 데이터가 있는 DB에서 HMAC 비밀값만 회전하거나 DB와 비밀값을 서로 다른 시점으로
  복구하지 않는다.
- PVC는 [비공개 Kubernetes 배포 실행서](docs/RUNBOOK/kubernetes-private-server-deployment.md)의
  신규 빈 DB 부분 초기화 조건을 모두 증명한 경우 외에는 삭제하지 않는다.
- `MYSQL_ROOT_HOST=localhost`와 역할별 DB 자격 증명, TLS `VERIFY_IDENTITY`, 경로별 외부 경계,
  NetworkPolicy와 probe는 중복 설정이 아니라 독립된 보안·운영 경계로 유지한다.
- 기존 데이터베이스에 HMAC 보호 장치를 처음 결합할 때는
  [보호 장치 최초 결합 실행서](docs/RUNBOOK/link-code-key-guard-binding.md)를 사용한다.
