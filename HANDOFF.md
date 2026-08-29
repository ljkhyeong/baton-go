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
  클러스터 검증이나 공개 운영 승인 증거가 아니다. MySQL 워크로드는 비루트 UID/GID와 Linux
  capability 제거를 선언하지만, Namespace의 `restricted` 강제 승격 전에는 고정 이미지로 신규·
  복원·현재 PVC의 기동과 TLS·초기화·마이그레이션을 실제 클러스터에서 검증해야 한다.
- 대표 HTTP 요청·응답은 기존 계약 테스트에서 REST Docs 조각으로 생성하며
  `:adapter-in-web:apiContractDocs`가 압축 산출물을 만들고 CI가 `baton-go-rest-docs`로
  보존한다. 보존 기간은 [CI 워크플로](.github/workflows/ci.yml)의 `retention-days` 설정을
  따른다. 제품 동작과 전체 오류 행렬의 정본은 계속
  [API 계약](docs/PRD/0002_api-contract/spec.md)이다.

## 외부 저장소 확인 기준

- 2026-08-27 BATON `2ddfed0bd6fbd5c4a4e1595110d3b46bb6ab32dd`에서 계정 세션,
  구성원 연결, ROUND 방 매핑·참여권·JWK와 선택 실행 경계 검증 구현을 확인했다.
- 2026-08-27 ROUND `a67df4ba89e935b623d58f2ce7542a5dbbbaea7f`에서 BATON 모드
  브라우저 진입, 참여권 검증, TURN·WebSocket 방 경계 구현을 확인했다.
- 위 커밋 확인은 실제 릴리스 이미지, 공개 HTTPS, 외부 coturn과 운영 자격 증명 검증을
  대신하지 않는다. BATON 저장소에서는 GO 원격 생성·폐기 호출과 순서 보장 아웃박스 구현을
  확인하지 못했다.

## 공개 운영 전 남은 관문

1. 배포 DB 전체를 [대상 계약 v1 정리 실행서](docs/RUNBOOK/target-contract-v1-remediation.md)로
   조사하고 `unrevoked non-compliant=0`, 미승인 `HOLD=0` 증거를 확보한다.
2. 확인한 BATON·ROUND 인증 경계를 실제 릴리스 이미지와 공개 HTTPS, 외부 coturn에 연결하고
   세션·CSRF·쿠키·JWK 회전·TURN·WebSocket 경계 증거를 확보한다. GO 관리 API는
   Spring Security JWT와 작업별 scope로 전환했으므로 실제 발급자 식별자·JWK, 서비스 신원,
   audience·scope와 키 회전을 비공개 경계에서 검증한다.
3. BATON 호출자에 GO 원격 생성·폐기 의도와 같은 `Idempotency-Key`를 소유 트랜잭션의
   순서 보장 아웃박스로 저장하고, 취소 표식과 생성 후 폐기 수렴을 구현한다.
4. 공개 `/l`과 비공개 `/api/v1` 외부 경계를 분리하고, 분산 요청률 제한과
   `/l/{code}` 접근 로그 마스킹을 적용한다.
5. 실제 비공개 클러스터에서 DNS·TLS·CNI·NetworkPolicy·startup/liveness/readiness probe,
   PVC와 Secret 수명주기를 검증한다. 현재 ingress 전용 NetworkPolicy에 더해 환경별 DNS·MySQL
   egress 허용 목록과 기본 차단 정책을 정하고 실제 CNI에서 허용·차단 증거를 확보한다.
6. 릴리스 이미지 다이제스트, SBOM, 취약점 검사, 서명·provenance 검증 결과를 보존하고
   승인된 이미지만 배포되는지 확인한다.
7. Prometheus 실제 수집, 경보 규칙, 알림 경로와 담당자를 연결하고 마이그레이션 실패,
   Pod 비정상, 5xx·429, DB 준비 상태, 저장 대상 계약 위반, PVC 용량과 백업 실패 경보의
   시험 증거를 확보한다.
8. 멱등 재생 보장 기간과 만료·폐기 링크 보존 기간, 자동 정리 뒤 HTTP 의미, 백업·감사
   보존과 PVC 경보·증설 기준을 함께 결정한다. 이 결정과 정리 구현 전에는 업무 행을
   자동 삭제하지 않는다.
9. 플랫폼 백업 정책에 RPO·RTO·주기·보존 기간·담당자·실패 경보·증거 위치를 명시하고,
   DB와 같은 버전의 `BATON_GO_LINK_CODE_SECRET`을 한 복구 단위로 사용한 격리 복원,
   장애 대응과 이미지 되돌리기 훈련을 완료한다.
10. HMAC 비밀값 유출 시 임의 Secret 회전 없이 쓰기·공개 경계를 차단하고, 기존 링크
    폐기·재발급 또는 버전별 키 묶음 도입 중 복구 방식을 결정해 훈련한다.
운영 실행과 복구 안전선은
[비공개 Kubernetes 배포 실행서](docs/RUNBOOK/kubernetes-private-server-deployment.md)와
[HMAC 키·DB 결합 결정](docs/ADR/0004_link-code-key-binding/adr.md)을 따른다.
