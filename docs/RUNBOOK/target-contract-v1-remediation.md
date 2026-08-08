# Target contract v1 inventory·정리 runbook

이 runbook은 PRD-0003 이전에 저장된 링크를 조사하고 non-compliant 링크를 안전하게
폐기하기 위한 절차다. API 구현이나 테스트 DB 정리는 production inventory 완료 증거를
대신하지 않는다.

## 1. 사전 조건

1. DB backup과 현재 `BATON_GO_LINK_CODE_SECRET` secret-manager version을 하나의 복구 단위로
   보존하고 canary 생성 intent replay를 확인한다.
2. exact target 검증이 포함된 binary를 먼저 배포한다. 정리가 끝날 때까지 그 이전 binary로
   rollback하지 않는다.
3. 구버전 writer, 직접 SQL 적재와 schema 변경을 중지한다.
4. 외부 네트워크에서 public origin의 operations 경로와 다른 `/api/v1` 관리 경로를 인증 없이
   probe한다. BATON GO의 `WWW-Authenticate: Bearer realm="baton-go-management"` 또는 GO
   `X-Request-Id`가 보이면 public edge가 관리 API까지 전달한 것이므로 진행하지 않는다.
5. public edge 차단 결과를 release evidence에 남긴 뒤 maintenance window에만 다음 두 값을
   모두 설정해 재기동한다. 확인 flag는 network boundary를 대신하지 않는다.

```text
BATON_GO_TARGET_CONTRACT_OPERATIONS_ENABLED=true
BATON_GO_TARGET_CONTRACT_OPERATIONS_PRIVATE_INGRESS_CONFIRMED=true
```

## 2. Read-only inventory

관리 credential을 shell history, process argument와 터미널 녹화에 남기지 않는다. token은
secret manager에서 현재 shell의 비export 변수로 읽고, curl argument가 아니라 stdin header로
전달한다. header 파일 입력은 curl config 문자열 문법을 거치지 않으므로 허용된 printable
ASCII의 따옴표와 백슬래시도 token 원문 그대로 보존한다. 다음 `read` 입력값은 화면에
표시되지 않는다.

```bash
read -r -s BATON_GO_OPS_TOKEN
printf '\n'
printf 'Authorization: Bearer %s\n' "$BATON_GO_OPS_TOKEN" | \
  curl --header @- -sS \
  'https://go.internal.example/api/v1/operations/link-target-contract-v1/inventory?limit=100'
```

`hasMore=true`이면 `nextAfterLinkId`를 다음 요청의 `afterLinkId`로 전달한다. 마지막 page까지
완주한다. 각 항목에는 raw target이나 코드가 없으며 다음 정보만 사용한다.

- `compliance`: `COMPLIANT` 또는 `NON_COMPLIANT`
- `remediationState`: `NOT_REQUIRED`, `UNREVOKED`, `REVOKED`
- `creationRequestState`: `PRESENT` 또는 `MISSING`
- `linkId`, `version`, 생성·만료·폐기 시각

승인 manifest에는 `linkId`, inventory의 `version`, action과 승인자만 기록한다. action은
다음 네 가지로 제한한다.

- `KEEP`: compliant 행
- `REVOKE_ONLY`: owner가 없거나 재발급이 불필요한 non-compliant 행
- `REISSUE_THEN_REVOKE`: 원본 owner가 canonical replacement를 먼저 만들 수 있는 행
- `HOLD`: owner, mapping 또는 데이터 정합성을 아직 확정하지 못한 행

target path, raw enum, code hash, short URL, idempotency hash와 이 값들의 digest를 manifest,
로그, 티켓 또는 CI artifact에 추가하지 않는다. `creationRequestState=MISSING`은 자동 복구하지
않고 원본 owner가 확인할 때까지 `HOLD`로 둔다.

## 3. 재발급

`REISSUE_THEN_REVOKE`는 원본 aggregate 소유자가 수행한다.

1. authoritative BATON/ROUND mapping을 먼저 commit한다.
2. 기존 intent를 재사용하지 않고 새 canonical UUID intent를 저장한다.
3. DB transaction 밖에서 정상 `POST /api/v1/links`를 호출한다.
4. 새 `linkId`와 short URL 저장·게시를 확인한다.
5. legacy target 문자열을 새 요청에 복사하거나 canonical 값으로 추측해 교정하지 않는다.

owner나 mapping을 확정할 수 없으면 재발급하지 않는다.

## 4. 승인된 개별 폐기

inventory 뒤 row가 변경되지 않았는지 확인하도록 승인 manifest의 `version`을 보낸다.

```bash
printf 'Authorization: Bearer %s\n' "$BATON_GO_OPS_TOKEN" | \
  curl --header @- -sS -X PUT \
  -H 'Content-Type: application/json' \
  'https://go.internal.example/api/v1/operations/link-target-contract-v1/links/<link-id>/revocation' \
  --data '{"expectedVersion":0}'
```

- compliant 행은 `409 REMEDIATION_NOT_APPLICABLE`이며 변경되지 않는다.
- inventory 뒤 변경된 미폐기 행은 `409 REMEDIATION_STALE`이며 다시 inventory·승인한다.
- 성공은 `200`이고, 반복 요청은 최초 `revokedAt`을 보존하며 `alreadyRevoked=true`를 반환한다.
- 행을 삭제하거나 target을 수정하지 않는다. 생성 예약도 삭제하지 않는다.

부분 실패 뒤에는 성공한 행을 포함해 같은 승인 목록을 다시 적용해도 안전하다. 단, stale이나
`HOLD`를 임의로 건너뛰고 gate를 완료 처리하면 안 된다.

## 5. 완료 검증

1. 처음부터 전체 inventory를 다시 실행한다.
2. 모든 `NON_COMPLIANT`가 `REVOKED`이고 `HOLD`가 0인지 확인한다.
3. `creationRequestState=MISSING`이 모두 조사·승인됐는지 확인한다.
4. valid link canary가 여전히 올바른 destination으로 redirect되는지 확인한다.
5. invalid link는 `Location` 없는 `404 LINK_NOT_FOUND`, 폐기한 valid link는 계약된 `410`인지
   확인한다.
6. 재발급한 링크는 원본 서비스가 authoritative mapping과 최종 권한을 확인하는지 검증한다.
7. operations enable과 private-ingress-confirmed를 모두 `false`로 되돌려 재기동하고
   인증된 내부 요청에도 endpoint가 `404 RESOURCE_NOT_FOUND`인지 확인한다.
8. inventory 실행 시각, 배포 SHA, 승인 manifest digest, 결과 집계와 canary 결과만 release
   evidence에 남긴다.

마지막 operations 호출 뒤 현재 shell에서 token 변수를 제거한다.

```bash
unset BATON_GO_OPS_TOKEN
```

비허용 target에 credential이 포함됐을 가능성이 있으면 이 절차의 성공과 별개로 credential을
회전하고 live DB·backup 보존 정책을 보안 사고 절차에 따라 검토한다.
