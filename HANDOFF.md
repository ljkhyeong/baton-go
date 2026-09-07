# 인수인계

## 현재 상태

- GO의 링크 생성·조회·접속 처리·폐기와 동일 요청 재시도는 구현되어 있다.
  동작 기준은 [제품 명세](docs/PRD/0001_product-baseline/spec.md),
  [API 계약](docs/PRD/0002_api-contract/spec.md),
  [BATON·ROUND 연동 계약](docs/PRD/0003_cross-service-link-contract/spec.md)이다.
  GO 링크는 위치만 제공하며 접근 권한은 BATON·ROUND가 판단한다.
- 종료 링크 자동 삭제, Redis 공용 요청 제한, 대상 계약 정리 API는 기본 중지 상태다.
  실제 운영 환경 검증과 배포는 남아 있다.
- BATON 연동 작업 공간은 `/private/tmp/baton-go-integration-20260905`, 브랜치는
  `codex/go-link-integration-20260905`다. 최근 확인 리비전은 `90557822`, 코드 기준은 `bc888b15`다.
  GO 연동·카카오톡 공유·QR 기능을 반영했으며 BATON 메인 병합과 실제 전송·스캔 검증은 남아 있다.
- 완료 내역과 이전 검증의 리비전·명령·로그는
  [구현·검증 기록](docs/HISTORY/2026-09-08-implementation-verification.md)에 보관한다.

## 최근 검증

- 2026-09-08 검증 기준은 GO `81fd4e2`다. 검증 대상 코드·테스트의 미커밋 변경은 없으며 이후에는 문서만 수정했다.
- 공개 오류 제목, 링크 대상·자동 삭제 오류와 HMAC 키 설정·등록 안내를 수정했다. HTML의 요청 제한 장애 제목은
  "지금은 링크를 열 수 없습니다."이며 JSON의 상세 원인, 오류 코드와 HTTP 상태는 유지했다.
- Java 21(`/Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home`)로 아래 기존 테스트 77개를
  모두 통과했다. 실패·제외는 없다. 로그는 `/private/tmp/baton-go-wording-tests-20260908.log`에 있다.

```bash
./gradlew --no-daemon :domain:test --tests '*TrustedTargetPolicyTest' \
  :adapter-out-external:test --tests '*LinkCodePropertiesTest' \
  :adapter-in-web:test --tests '*GlobalExceptionHandlerTest' \
  --tests '*DistributedResolverQuotaHttpIntegrationTest' --tests '*PublicLinkHttpContractTest' \
  :guard-tool:test --tests '*LinkCodeKeyGuardBindingCliTest'
```

- BATON `codex/go-link-integration-20260905`의 `90557822`에 공유 문구와 검증 기록을 반영했다.
  코드 기준은 `bc888b15`다. 타입·빌드 검사와 공유 시나리오 12개를 통과하고, 안내 위치를 맞춘 뒤
  직접 복사 3개만 추가 검증했다. 명령·포트 조건·로그는
  `/private/tmp/baton-go-integration-20260905/output/verification/latest.md`에 있다.
- 문구·표시 순서만 바뀌어 실제 MySQL·Redis·배포 검증은 반복하지 않았다. 실제 카카오 전송과 BATON 메인 병합은 남아 있다.

- 2026-09-08 추가 문서 정리는 `dc53975`에서 시작했다. 시작 시 미커밋 변경은 없었다.
  동일 요청 재시도와 기존 URL 복원을 구분하고 Redis 시간 구간·키 등록·자동 삭제 기록의 표현을 맞췄다.
  문서 15개의 로컬 링크 79개(제목 링크 11개 포함)를 확인하고 `git diff --check`를 통과했다.
  명령·설정 예시와 오류 코드·설정 식별자는 유지했다. 결과는 `/private/tmp/baton-go-doc-round3-check-20260908.log`에 있다.
  문서만 변경해 빌드·테스트는 반복하지 않았다.

## 다음 작업

1. 배포 DB 전체를 [대상 계약 v1 정리 절차](docs/RUNBOOK/target-contract-v1-remediation.md)로
   조사하고 `unrevoked non-compliant=0`, 미승인 `HOLD=0` 증거를 확보한다.
2. 확인한 BATON·ROUND 인증 경계를 실제 릴리스 이미지와 공개 HTTPS, 외부 coturn에 연결하고
   세션·CSRF·쿠키·JWK 회전·TURN·WebSocket 경계 증거를 확보한다. GO 관리 API는
   Spring Security JWT와 작업별 scope로 전환했으므로 실제 발급자 식별자·JWK, 서비스 신원,
   audience·scope와 키 회전을 비공개 경계에서 검증한다.
3. 최신 BATON 변경과 GO 연동 브랜치를 병합하고 충돌한 동작을 검증한다.
   GO 연동과 이후 BATON·공휴일 기능을 함께 유지한다. 미배포 GO 마이그레이션 V40~V42는
   계정 비활성화 V39 다음 순서이며, 운영 DB에 적용한 파일은 교체하지 않는다.
   고정 GO 커밋 `b4b4df22cbabf7a71697d60db5294ecfeb857e86`의 원격 반영과 BATON Actions의
   `BATON_GO_CONTRACT_READ_TOKEN` 등록을 확인한 뒤 GitHub 품질 게이트를 실행한다.
   실제 서비스 JWT와 HTTPS 환경에서 생성 응답 유실 뒤 취소·폐기 완료까지의 재시도,
   상태 확인 장애 경보와 생성·폐기 및 상태 확인 묶음 처리를 검증한다.
   상태 확인·링크 생성 요청 저장·GO API 호출은 기본 중지하며 기존 방의 링크 일괄 생성은 포함하지 않는다.
   카카오 앱의 무료 사용 설정·실제 메시지 전송과 실기기 QR 스캔을 확인한다.
4. 공개 `/l`과 비공개 `/api/v1` 외부 경계를 분리하고, 분산 요청률 제한과
   `/l/{code}` 접근 로그 마스킹을 적용한다. 공용 제한 구현은 준비되었으며 실제 Redis·Ingress 검증은 남아 있다.
5. 실제 비공개 클러스터에서 DNS·TLS·CNI·NetworkPolicy·startup/liveness/readiness probe,
   PVC와 Secret 수명주기를 검증한다. 현재 ingress 전용 NetworkPolicy에 더해 환경별 DNS·MySQL
   egress 허용 목록과 기본 차단 정책을 정하고 실제 CNI에서 허용·차단 증거를 확보한다.
   `restricted` 정책 강제 적용 전에는 고정 MySQL 이미지로 신규·복원·현재 PVC의 기동,
   TLS·초기화·마이그레이션을 검증한다.
6. 릴리스 이미지 다이제스트, SBOM, 취약점 검사, 서명·provenance 검증 결과를 보존하고
   승인된 이미지만 배포되는지 확인한다.
7. Prometheus 실제 수집, 경보 규칙, 알림 경로와 담당자를 연결하고 마이그레이션 실패,
   Pod 비정상, 5xx·429, DB 준비 상태, 저장 대상 계약 위반, PVC 용량과 백업 실패 경보의
   시험 증거를 확보한다.
8. 재시도 시 기존 URL 반환 보장 기간과 만료·폐기 링크 보존 기간, 자동 정리 뒤 HTTP 의미, 백업·감사
   보존과 PVC 경보·증설 기준을 함께 결정한다. 정리 구현은 준비되어 있으며 이 결정 전에는
   자동 정리를 활성화하지 않는다.
9. 플랫폼 백업 정책에 RPO·RTO·주기·보존 기간·담당자·실패 경보·증거 위치를 명시하고,
   DB와 같은 버전의 `BATON_GO_LINK_CODE_SECRET`을 한 복구 단위로 사용한 격리 복원,
   장애 대응과 이미지 되돌리기 훈련을 완료한다.
10. HMAC 비밀값 유출 시 임의 Secret 회전 없이 쓰기·공개 경계를 차단하고, 기존 링크
    폐기·재발급과 키 묶음 전환을 결합한 복구 방식을 결정해 훈련한다.
    평상시 키 교체도 [운영 절차](docs/RUNBOOK/link-code-key-rotation.md)에 따라 배포·전환·복원을 검증한다.

운영·복구 절차는
[비공개 Kubernetes 배포 절차](docs/RUNBOOK/kubernetes-private-server-deployment.md)와
[HMAC 키·DB 결합 결정](docs/ADR/0004_link-code-key-binding/adr.md)을 따른다.
