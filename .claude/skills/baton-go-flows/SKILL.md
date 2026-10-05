---
name: baton-go-flows
description: BATON GO의 링크 생성·재시도·폐기·공개 조회, 관리 API, 코드 발급 키, DB 스키마, 배포·운영 설정을 변경할 때 사용한다. 필요한 기준 문서와 변경 시 깨지기 쉬운 불변식을 안내한다. 단순 문구·주석·문서 수정에는 사용하지 않는다.
---

# BATON GO 작업 흐름

규칙·모듈 의존·검증 원칙은 저장소 루트의 `AGENTS.md`, 현재 상태와 다음 작업은 `HANDOFF.md`가 기준이다.
아래 경로는 모두 저장소 루트 기준 상대 경로이므로 별도 작업 트리에서도 같은 경로를 읽는다.
사용자의 명시적 요청이 이 스킬의 권고와 다르면 사용자 요청을 따른다.

## 시작

1. `git rev-parse HEAD`와 `git status --short`로 시작 리비전과 기존 변경을 기록한다.
2. `HANDOFF.md`의 `현재 상태`와 `다음 작업`에서 이번 변경과 관련된 항목만 확인한다.
3. 아래 표에서 변경에 필요한 문서만 골라 읽는다. 제품 동작은
   `docs/PRD/0001_product-baseline/spec.md`, HTTP 동작은 `docs/PRD/0002_api-contract/spec.md`가 기준이다.

## 변경별 기준 문서

| 변경 | 확인할 문서 |
| --- | --- |
| 링크 생성·재시도 | `docs/ADR/0003_idempotent-link-creation/adr.md`, `docs/ADR/0009_idempotent-public-origin-replay/adr.md` |
| 코드 형식·해시 저장·공개 조회 | `docs/ADR/0002_link-security/adr.md` |
| 대상 경로·BATON·ROUND 연동 | `docs/PRD/0003_cross-service-link-contract/spec.md`, `docs/ADR/0005_trusted-target-locator/adr.md` |
| 관리 인증·scope·작업 이력 | `docs/ADR/0010_management-jwt-authentication/adr.md`, `docs/RUNBOOK/management-operation-history.md` |
| HMAC 키 묶음·교체 | `docs/ADR/0011_link-code-key-ring/adr.md`, `docs/RUNBOOK/link-code-key-rotation.md` |
| 종료 링크 정리 | `docs/ADR/0012_link-retention/adr.md`, `docs/RUNBOOK/link-retention.md` |
| 공개 조회 요청 제한·Redis | `docs/ADR/0013_distributed-public-resolver-quota/adr.md`, `docs/RUNBOOK/distributed-public-rate-limit.md` |
| MySQL 시각 저장·스키마 | `docs/ADR/0007_mysql-instant-storage/adr.md`, [DB 마이그레이션 점검](references/db-migration.md) |
| Kubernetes·DB 구성·복구 | `docs/ADR/0008_private-kubernetes-database-topology/adr.md`, `docs/RUNBOOK/kubernetes-private-server-deployment.md` |
| 경보·외부 감시·웹훅 | `docs/RUNBOOK/prometheus-alerts.md`, `docs/RUNBOOK/external-availability-monitoring.md`, `docs/RUNBOOK/external-api-integrations.md` |
| 이미지 검사·릴리스 자료 | `docs/RUNBOOK/image-security-reports.md` |
| REST Docs 예시 | `docs/API/rest-docs.md` |

## 변경 시 지킬 불변식

- 재시도는 예약에 저장한 발급 키 버전과 공개 origin으로 최초 URL을 반환한다. 필요한 저장값이 없으면
  현재 설정으로 대체하지 않고 오류를 반환한다. 동시 생성·폐기·정리는 DB 제약과 잠금으로 충돌을 처리한다.
- 검증을 줄일 때 HMAC 키와 DB의 일치 확인, 재시도 요청 비교, 저장된 대상의 재검사를
  단순 중복으로 지우지 않는다. 각각 다른 입력·저장 상태를 확인한다.
- 입력 검증은 규칙을 소유한 계층에 둔다. 정리할 때 오류 코드, HTTP 상태와 거부 시점(서비스 호출 전·후)을 보존한다.
- 공개 `GET·HEAD /l/{code}`는 상태나 사용 횟수를 바꾸지 않는다. 요청 제한은 DB 조회 전에 적용하고
  클라이언트 IP·전달 헤더를 사용하지 않는다.
- 스키마를 바꾸면 [DB 마이그레이션 점검](references/db-migration.md)을 따른다.
  이미지 롤백은 DB 변경을 되돌리지 않는다. DB와 해당 시점의 발급 키 묶음을 함께 복구하며,
  운영 데이터가 있으면 HMAC 키나 DB 비밀번호 Secret만 단독으로 교체하지 않는다.
- 다른 저장소(BATON·ROUND)가 요청 범위에 포함되면 해당 체크아웃의 지시를 먼저 확인한다.
  BATON·ROUND의 최종 접근 권한 판단과 트랜잭션 경계를 GO로 옮기지 않는다.
- 동작을 바꾸면 같은 변경에서 관련 PRD·ADR·RUNBOOK을 갱신한다. Spring·라이브러리 기본 동작과
  계약 의미가 달라 직접 구현했다면 그 차이를 테스트나 문서에 남긴다.

## 이어지는 단계

- 검증 명령 선택: `baton-go-verify` 스킬
- 보안 불변식 점검이 필요한 변경(인증·로그·대상 검증·키): `baton-go-security-review` 스킬
- 기록·커밋으로 마무리: `baton-go-wrap-up` 스킬

보고할 때는 코드 구현, 로컬 검증(일반 테스트와 MySQL·Redis 통합 검증 구분), 원격 CI, 실제 배포 완료를 구분한다.
