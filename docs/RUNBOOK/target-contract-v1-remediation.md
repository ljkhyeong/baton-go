# 대상 계약 v1 목록 조사·정리 절차

이 운영 절차는 PRD-0003 이전에 저장된 링크를 조사하고 대상 규칙을 위반한 링크를
폐기하기 위한 절차다. API 구현이나 테스트 DB 정리는 운영 목록 조사 완료 증거를
대신하지 않는다.

## 1. 사전 조건

1. DB 백업과 현재 `BATON_GO_LINK_CODE_SECRET` 비밀값 관리자 버전을 하나의 복구 단위로
   보존하고 검증용 링크 생성 요청을 재시도해 기존 결과를 반환하는지 확인한다.
2. 정확한 대상 검증이 포함된 실행 파일을 먼저 배포한다. 정리가 끝날 때까지 그 이전 실행 파일로
   롤백하지 않는다.
3. 이전 버전의 링크 저장 프로세스, 직접 SQL 적재와 스키마 변경을 중지한다.
4. 외부 네트워크에서 공개 출처의 운영 경로와 다른 `/api/v1` 관리 경로를 인증 없이
   탐색한다. BATON GO의 `WWW-Authenticate: Bearer realm="baton-go-management"` 또는 GO
   `X-Request-Id`가 보이면 공개 엣지가 관리 API까지 전달한 것이므로 진행하지 않는다.
5. 공개 엣지 차단 결과를 배포 증거에 남긴 뒤 유지보수 시간대에만 다음 두 값을
   모두 설정해 재기동한다. 이 설정만으로 관리 API의 외부 접근이 차단되지는 않는다.

```text
BATON_GO_TARGET_CONTRACT_OPERATIONS_ENABLED=true
BATON_GO_TARGET_CONTRACT_OPERATIONS_PRIVATE_INGRESS_CONFIRMED=true
```

## 2. 읽기 전용 목록 조사

`baton-go.target-contract.operate` scope와 `aud=baton-go`를 가진 짧은 수명의 관리 JWT를
승인된 발급 절차로 받는다. JWT를 셸 기록, 프로세스 인자와 터미널 녹화에 남기지 않고 현재
셸의 export하지 않은 변수로 읽어 curl 인자가 아닌 표준 입력 헤더로 전달한다. 기존 작업과
분리한 전용 유지보수 셸에서 종료·인터럽트 정리 `trap`을 먼저 등록한다. 다음 `read` 입력값은
화면에 표시되지 않는다.

```bash
trap 'unset BATON_GO_OPS_TOKEN' EXIT HUP INT TERM
read -r -s BATON_GO_OPS_TOKEN
printf '\n'
printf 'Authorization: Bearer %s\n' "$BATON_GO_OPS_TOKEN" | \
  curl --header @- -sS \
  'https://go.internal.example/api/v1/operations/link-target-contract-v1/inventory?limit=100'
```

`hasMore=true`이면 `nextAfterLinkId`를 다음 요청의 `afterLinkId`로 전달한다. 마지막 페이지까지
조회한다. 각 항목에는 원시 대상이나 코드가 없으며 다음 정보만 사용한다.

- `compliance`: `COMPLIANT` 또는 `NON_COMPLIANT`
- `remediationState`: `NOT_REQUIRED`, `UNREVOKED`, `REVOKED`
- `creationRequestState`: `PRESENT` 또는 `MISSING`
- `linkId`, `version`, 생성·만료·폐기 시각

승인 명세에는 `linkId`, 목록 조사의 `version`, 조치와 승인자만 기록한다. 조치는
다음 네 가지로 제한한다.

- `KEEP`: 대상 규칙을 충족하는 링크
- `REVOKE_ONLY`: 소유자가 없거나 재발급이 불필요한 규칙 위반 링크
- `REISSUE_THEN_REVOKE`: 원본 소유자가 정규 대체 링크를 먼저 만들 수 있는 행
- `HOLD`: 소유자, 매핑 또는 데이터 정합성을 아직 확정하지 못한 행

대상 경로, 원시 열거형, 코드 해시, 단축 URL, 멱등성 해시와 이 값들의 다이제스트를 명세,
로그, 티켓 또는 CI 산출물에 추가하지 않는다. `creationRequestState=MISSING`은 자동 복구하지
않고 원본 소유자가 확인할 때까지 `HOLD`로 둔다.

## 3. 재발급

`REISSUE_THEN_REVOKE`는 원본 데이터를 관리하는 서비스가 수행한다.

1. 기준 BATON/ROUND 매핑을 먼저 커밋한다.
2. 기존 요청 UUID를 재사용하지 않고 새 정규 UUID로 링크 생성 요청을 저장한다.
3. DB 트랜잭션 밖에서 정상 `POST /api/v1/links`를 호출한다.
4. 새 `linkId`와 단축 URL 저장·게시를 확인한다.
5. 이전 대상 문자열을 새 요청에 복사하거나 정규 값으로 추측해 교정하지 않는다.

소유자나 매핑을 확정할 수 없으면 재발급하지 않는다.

## 4. 승인된 개별 폐기

목록 조사 뒤 행이 변경되지 않았는지 확인하도록 승인 명세의 `version`을 보낸다.

```bash
printf 'Authorization: Bearer %s\n' "$BATON_GO_OPS_TOKEN" | \
  curl --header @- -sS -X PUT \
  -H 'Content-Type: application/json' \
  'https://go.internal.example/api/v1/operations/link-target-contract-v1/links/<link-id>/revocation' \
  --data '{"expectedVersion":0}'
```

- 대상 규칙을 충족하는 링크는 `409 REMEDIATION_NOT_APPLICABLE`이며 변경되지 않는다.
- 목록 조사 뒤 변경된 미폐기 행은 `409 REMEDIATION_STALE`이며 다시 조사·승인한다.
- 성공은 `200`이고, 반복 요청은 최초 `revokedAt`을 보존하며 `alreadyRevoked=true`를 반환한다.
- 행을 삭제하거나 대상을 수정하지 않는다. 생성 예약도 삭제하지 않는다.

부분 실패 뒤에는 성공한 행을 포함해 같은 승인 목록을 다시 적용해도 안전하다. 단, 변경된 행이나
`HOLD`를 임의로 건너뛰고 배포 전 데이터 점검을 완료로 처리하면 안 된다.

## 5. 완료 검증

1. 처음부터 전체 목록 조사를 다시 실행한다.
2. 모든 `NON_COMPLIANT`가 `REVOKED`이고 `HOLD`가 0인지 확인한다.
3. `creationRequestState=MISSING`이 모두 조사·승인됐는지 확인한다.
4. 유효한 링크 검증값이 여전히 올바른 목적지로 리다이렉트되는지 확인한다.
5. 유효하지 않은 링크는 `Location` 없는 `404 LINK_NOT_FOUND`, 폐기한 유효한 링크는 계약된 `410`인지
   확인한다.
6. 재발급한 링크는 원본 서비스가 기준 매핑과 최종 권한을 확인하는지 검증한다.
7. 운영 기능 활성화 값과 비공개 인그레스 확인 값을 모두 `false`로 되돌려 재기동하고
   인증된 내부 요청에도 엔드포인트가 `404 RESOURCE_NOT_FOUND`인지 확인한다.
8. 목록 조사 실행 시각, 배포 SHA, 승인 명세 다이제스트, 결과 집계와 검증 결과만 배포
   증거에 남긴다.

마지막 운영 API 호출 뒤 토큰 변수를 제거하고 전용 유지보수 셸의 정리 `trap`을 해제한다.

```bash
unset BATON_GO_OPS_TOKEN
trap - EXIT HUP INT TERM
```

비허용 대상에 자격 증명이 포함됐을 가능성이 있으면 이 절차의 성공과 별개로 자격 증명을
회전하고 운영 DB·백업 보존 정책을 보안 사고 절차에 따라 검토한다.
