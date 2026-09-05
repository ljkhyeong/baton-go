# 인수인계

## 현재 기준

- BATON GO의 링크 생성·재생·조회·공개 해석·폐기와 v1 신뢰 대상 정책은 구현되어 있다.
  제품 동작은 [제품 기준선](docs/PRD/0001_product-baseline/spec.md), HTTP 동작은
  [API 계약](docs/PRD/0002_api-contract/spec.md)을 정본으로 삼는다.
- 관리 조회·폐기는 공개 해석과 같은 시간·폐기 정책의 이용 상태와 판정 시각을 반환한다.
  생성·재생 응답과 저장 스키마는 유지하며 BATON·ROUND 접근 권한을 나타내지 않는다.
- 일반 관리 목록은 기존 읽기 scope로 대상 시스템·생성 기간·이용 상태를 검색한다.
  검사량을 제한하고 필터로 빈 페이지가 되어도 다음 커서로 진행하며, 비허용 저장 대상은 제외한다.
- 공개 링크의 HTML 안내는 GO의 요청 제한·서버 오류도 포함한다. JSON 클라이언트와 관리
  인증 오류는 기존 형식을 유지하며, 요청 제한 화면의 대기 시간은 `Retry-After`와 같다.
- BATON·ROUND 위치 식별자와 최종 권한 경계는
  [교차 서비스 링크 계약](docs/PRD/0003_cross-service-link-contract/spec.md)을 따른다.
  GO 링크는 위치만 제공하며 BATON 접근 권한이나 ROUND 입장 권한을 부여하지 않는다.
- 링크 코드 HMAC 보호 장치, 멱등 재생의 공개 출처 보존과 MySQL 절대 시각 저장 결정은
  각각 [ADR-0004](docs/ADR/0004_link-code-key-binding/adr.md),
  [ADR-0009](docs/ADR/0009_idempotent-public-origin-replay/adr.md),
  [ADR-0007](docs/ADR/0007_mysql-instant-storage/adr.md)을 따른다.
- V7는 정리 표식과 요청 비교값을 추가한다. 종료 링크 자동 정리는 기본 중지하며
  [보존 실행서](docs/RUNBOOK/link-retention.md)에 따라 기간을 명시해야 활성화된다.
- V6는 기존 예약을 `legacy` 키로 이관하고 새 예약에 발급 키 ID를 저장한다. 키 교체와
  이전 키 제거 조건은 [ADR-0011](docs/ADR/0011_link-code-key-ring/adr.md)을 따른다.
  실제 운영 키 묶음 배포·전환·복원 훈련은 [실행서](docs/RUNBOOK/link-code-key-rotation.md)에 따라 남아 있다.
- 계약 전 저장 데이터 조사·폐기 API는 구현되어 있지만 기본 비활성화 상태다. 공개 경계 차단을
  확인한 유지 보수 시간에만 두 활성화 값을 함께 사용하며, 일반 운영에서는 모두 `false`로 둔다.
- Redis 공용 요청 제한은 기본 중지하며 [분산 제한 실행서](docs/RUNBOOK/distributed-public-rate-limit.md)에
  따라 운영 Redis를 연결해야 한다. 두 연결 동시성·실패 차단과 공개 HTTP 검증은 준비되어 있다.
- GO 전용 MySQL과 비공개 Kubernetes 기본 구성은 준비되어 있다. 이는 배포 기반일 뿐 실제
  클러스터 검증이나 공개 운영 승인 증거가 아니다. MySQL 워크로드는 비루트 UID/GID와 Linux
  capability 제거를 선언하지만, Namespace의 `restricted` 강제 승격 전에는 고정 이미지로 신규·
  복원·현재 PVC의 기동과 TLS·초기화·마이그레이션을 실제 클러스터에서 검증해야 한다.
- 공개·관리 응답 지연과 전체·관리 API 5xx·관리 JWT 서비스 장애·관리 링크 복구 오류·429·저장 대상 계약 위반의 [Prometheus 경보 규칙](deploy/prometheus/baton-go-alerts.yml)과
  `promtool` 검증은 준비되어 있다. 실제 수집기·알림 경로 연결과 인프라·백업 경보는
  [경보 실행서](docs/RUNBOOK/prometheus-alerts.md)에 따라 운영 환경에서 검증해야 한다.
- CI는 검사한 커밋·이미지 구성 다이제스트·아카이브 체크섬과 SBOM·취약점 보고서를
  `baton-go-image-security`로 보존한다. 취약점 등급별 자동 차단이나 릴리스 이미지 승인 증거는
  아니며, [이미지 검사 실행서](docs/RUNBOOK/image-security-reports.md)에 따라 결과를 검토한다.
- 대표 HTTP 요청·응답은 기존 계약 테스트에서 REST Docs 조각으로 생성하며
  `:adapter-in-web:apiContractDocs`가 압축 산출물을 만들고 CI가 `baton-go-rest-docs`로
  보존한다. 보존 기간은 [CI 워크플로](.github/workflows/ci.yml)의 `retention-days` 설정을
  따른다. 제품 동작과 전체 오류 행렬의 정본은 계속
  [API 계약](docs/PRD/0002_api-contract/spec.md)이다.
- 관리 쓰기 완료 이력은 JWT `sub`와 내부 링크·요청 ID만 사용하며 기존 중앙 로그 보존 정책을
  따른다. 별도 감사 DB는 없으며, 실제 수집·조회 권한과 보존 정책 확인은
  [관리 작업 이력 실행서](docs/RUNBOOK/management-operation-history.md)에 따라 운영 환경에서 수행한다.

## 외부 저장소 확인 기준

- 2026-08-27 BATON `2ddfed0bd6fbd5c4a4e1595110d3b46bb6ab32dd`에서 계정 세션,
  구성원 연결, ROUND 방 매핑·참여권·JWK와 선택 실행 경계 검증 구현을 확인했다.
- 2026-08-27 ROUND `a67df4ba89e935b623d58f2ce7542a5dbbbaea7f`에서 BATON 모드
  브라우저 진입, 참여권 검증, TURN·WebSocket 방 경계 구현을 확인했다.
- 위 커밋 확인은 실제 릴리스 이미지, 공개 HTTPS, 외부 coturn과 운영 자격 증명 검증을
  대신하지 않는다.
- 2026-09-05 BATON `/Users/lim/devProject/personal/manager`의 별도
  `codex/go-link-integration-20260905` 브랜치에 ROUND 방의 GO 생성·폐기 전달, 인증된 방 조회와
  화면의 단축 링크 상태·복사, 운영 실패 조회·재처리와 이전 공개 도메인 재생을 구현했다.
  실제 서명 JWT·두 MySQL·BATON HTTP·GO 실행 파일로 생성 응답 유실 뒤 도메인 변경·취소·폐기를
  검증했다. 원본에 커밋된 V34 팀 권한 이관과의 병합, 그 이후 GO V35·V36 적용, 실제 운영
  발급자·HTTPS 검증은 남아 있다. 기존 방 일괄 생성은 포함하지 않는다.
  추가로 GO에서 직접 폐기한 링크의 백그라운드 상태 확인, 전달 중지·2분 지연 화면 안내와
  기본 5분 대기 경보를 구현했다. 실제 GO 관리 폐기 후 BATON 종료 응답과 URL 제거도 검증했다.
  상태 확인은 기본 중지하며 운영 JWT 읽기 scope와 설정 활성화,
  대기 점검 실패의 실제 알림 경로 연결이 필요하다.

## 공개 운영 전 남은 관문

1. 배포 DB 전체를 [대상 계약 v1 정리 실행서](docs/RUNBOOK/target-contract-v1-remediation.md)로
   조사하고 `unrevoked non-compliant=0`, 미승인 `HOLD=0` 증거를 확보한다.
2. 확인한 BATON·ROUND 인증 경계를 실제 릴리스 이미지와 공개 HTTPS, 외부 coturn에 연결하고
   세션·CSRF·쿠키·JWK 회전·TURN·WebSocket 경계 증거를 확보한다. GO 관리 API는
   Spring Security JWT와 작업별 scope로 전환했으므로 실제 발급자 식별자·JWK, 서비스 신원,
   audience·scope와 키 회전을 비공개 경계에서 검증한다.
3. 별도 BATON 연동 브랜치의 생성 의도·취소·결과 회수·화면·운영 도구를 원본 작업과 병합하고,
   실제 서비스 JWT와 HTTPS 환경에서 생성 응답 유실 뒤 취소·폐기 수렴을 검증한다.
4. 공개 `/l`과 비공개 `/api/v1` 외부 경계를 분리하고, 분산 요청률 제한과
   `/l/{code}` 접근 로그 마스킹을 적용한다. 공용 제한 구현은 준비되었으며 실제 Redis·Ingress 검증은 남아 있다.
5. 실제 비공개 클러스터에서 DNS·TLS·CNI·NetworkPolicy·startup/liveness/readiness probe,
   PVC와 Secret 수명주기를 검증한다. 현재 ingress 전용 NetworkPolicy에 더해 환경별 DNS·MySQL
   egress 허용 목록과 기본 차단 정책을 정하고 실제 CNI에서 허용·차단 증거를 확보한다.
6. 릴리스 이미지 다이제스트, SBOM, 취약점 검사, 서명·provenance 검증 결과를 보존하고
   승인된 이미지만 배포되는지 확인한다.
7. Prometheus 실제 수집, 경보 규칙, 알림 경로와 담당자를 연결하고 마이그레이션 실패,
   Pod 비정상, 5xx·429, DB 준비 상태, 저장 대상 계약 위반, PVC 용량과 백업 실패 경보의
   시험 증거를 확보한다.
8. 멱등 재생 보장 기간과 만료·폐기 링크 보존 기간, 자동 정리 뒤 HTTP 의미, 백업·감사
   보존과 PVC 경보·증설 기준을 함께 결정한다. 정리 구현은 준비되어 있으며 이 결정 전에는
   자동 정리를 활성화하지 않는다.
9. 플랫폼 백업 정책에 RPO·RTO·주기·보존 기간·담당자·실패 경보·증거 위치를 명시하고,
   DB와 같은 버전의 `BATON_GO_LINK_CODE_SECRET`을 한 복구 단위로 사용한 격리 복원,
   장애 대응과 이미지 되돌리기 훈련을 완료한다.
10. HMAC 비밀값 유출 시 임의 Secret 회전 없이 쓰기·공개 경계를 차단하고, 기존 링크
    폐기·재발급과 키 묶음 전환을 결합한 복구 방식을 결정해 훈련한다.
운영 실행과 복구 안전선은
[비공개 Kubernetes 배포 실행서](docs/RUNBOOK/kubernetes-private-server-deployment.md)와
[HMAC 키·DB 결합 결정](docs/ADR/0004_link-code-key-binding/adr.md)을 따른다.
