# 링크 코드 키 유출 대응

`BATONGO_LINKCODE_KEYS_*` 비밀값의 유출이 확인되거나 의심될 때 따른다. 평상시 교체는
[키 교체 절차](link-code-key-rotation.md)다. 키 교체만으로는 이미 만든 링크가 무효화되지 않는다.
공개 조회는 저장된 코드 해시로 링크를 찾으므로, 유출된 키로 만든 링크는 폐기해야만 닫힌다.

비밀값·지문·멱등성 키·코드 해시·전체 단축 URL·대상 경로는 티켓·사고 기록·명령 인자에 넣지 않는다.
사고 기록에는 키 ID, 링크 ID, 건수, 시각, 승인자만 남긴다.

## 영향 판단

공개 코드는 키 비밀값과 호출자가 보관한 멱등성 키 원문을 함께 넣은 HMAC에서 나온다. GO DB에는 멱등성 키의
SHA-256 해시만 있으므로 비밀값만으로는 코드를 계산할 수 없다. 보안 담당자는 다음을 정한다.

- 유출된 키 ID: 비밀값 관리자의 버전·접근 기록으로 정한다. 확인할 수 없으면 설정된 모든 키를 유출로 본다.
- 호출자 멱등성 키의 함께 노출 여부: BATON 아웃박스·DB·로그가 함께 노출됐거나 확인할 수 없거나, 호출자가
  무작위 UUID v4가 아닌 키(v1·v3·v5)를 썼다면 노출로 본다. 이때는 해당 키로 만든 링크를 모두 폐기·재발급한다.
- 비밀값만 노출됐고 위 조건이 아니라고 확인되면 새 링크의 발급 키 전환은 필수이고, 기존 링크의 폐기 범위는
  보안 담당자와 링크를 만든 서비스 담당자가 함께 정한다.
- GO 링크는 접근 권한을 주지 않는다. 코드가 계산됐더라도 BATON·ROUND의 최종 권한 검사가 대상 화면을 막는지
  해당 서비스 담당자가 함께 확인한다.

## 1. 차단

1. 외부 프록시에서 공개 `/l`과 비공개 `/api/v1` 경로를 막고 BATON 호출자의 아웃박스 전송을 멈춘다.
2. Secret을 바꾸거나 `rollout restart`를 먼저 하지 않는다. 같은 키 ID의 값을 바꾸면 시작 검사가 거부한다.
3. 현재 DB의 일관된 백업, 배포 이미지 다이제스트, Flyway 버전과 비밀값 관리자의 버전 식별자를 제한된 사고
   저장소에 보존한다. 비밀값과 지문은 복사하지 않는다.

애플리케이션 Pod는 내부 운영 작업을 위해 유지한다. 클러스터 내부 접근까지 의심되면 복제본을 `0`으로 줄였다가
2단계의 새 키 묶음으로 다시 시작한다.

## 2. 발급 키 전환

[키 교체 절차](link-code-key-rotation.md#일반-교체-순서)대로 새 키 ID와 새 비밀값을 모든 Pod에 추가한 뒤
`BATONGO_LINKCODE_ACTIVEKEYID`를 새 ID로 바꾼다. 새 비밀값은 유출 경로와 분리된 비밀값 관리자에서 만든다.
유출된 키는 키 묶음에서 지우지 않는다. 정리되지 않은 생성 예약이 쓰는 키가 빠지면 시작 검사가 거부하며,
남은 키는 이미 만든 링크의 동일 요청 재시도에만 쓰인다. 전환 뒤 아래 내부 경로에서 시험 링크를 하나 만들어
새 키 ID로 저장되는지 다음 조회로 확인한다.

```sql
SELECT key_id, COUNT(*) AS reservations
FROM link_creation_requests
WHERE purged_at IS NULL
GROUP BY key_id;
```

## 3. 영향 링크 폐기·재발급

유출된 키로 만들었고 아직 폐기하지 않은 링크 ID를 운영 DB 접근 권한으로 조회해 사고 저장소에만 보관한다.

```sql
SELECT BIN_TO_UUID(s.id) AS link_id
FROM link_creation_requests r JOIN smart_links s ON s.id = r.link_id
WHERE r.key_id = 'compromised-key-id' AND r.purged_at IS NULL AND s.revoked_at IS NULL
ORDER BY s.id;
```

`compromised-key-id`를 실제 키 ID로 바꾼다. 링크를 만든 서비스가 목록의 링크를 아웃박스로 폐기하고, 계속 필요한
원본에는 **새 멱등성 키**로 새 링크를 만든다. 같은 키로 다시 요청하면 폐기된 기존 링크와 기존 단축 URL이
돌아온다. 공유된 메시지·QR 코드는 새 단축 URL로 다시 안내한다.

GO 운영자가 먼저 닫아야 하면 `baton-go.links.revoke` scope의 운영 서비스 JWT로 내부 경로에서 폐기한다.
토큰은 명령 인자 대신 권한 `0600` 파일의 헤더로 전달하고 작업이 끝나면 파일을 지운다. 폐기는 멱등이며 완료
이력에 `serviceId`가 남는다.

```bash
kubectl -n baton-go port-forward deployment/baton-go 18080:8080
# 다른 터미널. affected-link-ids.txt는 위 조회 결과, authorization.header는 "Authorization: Bearer ..." 한 줄이다.
while read -r link_id; do
  curl --fail --silent --show-error --output /dev/null --request PUT \
    --header @authorization.header \
    "http://127.0.0.1:18080/api/v1/links/${link_id}/revocation"
done < affected-link-ids.txt
```

목록 조회를 다시 실행해 결과가 없으면 폐기를 마친 것이다. 차단 중 만료된 링크도 목록에 남아 있으면 폐기한다.

## 4. 재개와 확인

보안 담당자와 링크를 만든 서비스 담당자의 승인을 받은 뒤 `/api/v1`, 아웃박스 전송, `/l` 순서로 연다.
새 링크의 생성·동일 요청 재시도·리다이렉트와 폐기한 링크의 `410 LINK_REVOKED`를 확인한다.
`BatonGoManagementLinkRecoveryFailure`와 5xx 경보가 없는지도 본다.

## 5. 유출 키 제거와 기록

유출된 키의 비밀값은 그 키를 쓰는 정리되지 않은 생성 예약이 없어진 뒤에만 실행 설정에서 지운다. 폐기한 링크는
[종료 링크 보존 기간](link-retention.md)이 지나 자동 정리되어야 예약도 정리되므로, 자동 정리를 켜지 않았다면
키를 계속 둔다. 등록 키 메타데이터는 지우지 않고 같은 키 ID를 다시 쓰지 않는다.

사고 기록에 탐지·차단·전환·폐기·재개 시각, 키 ID, 폐기·재발급 건수, 승인자와 남은 키 제거 예정 조건을 남긴다.

## 훈련

운영 데이터가 없는 격리 환경에서 키 A로 링크를 만든 뒤 위 절차로 키 B 전환, 키 A 링크 폐기, 새 키 재발급과
재개를 수행한다. 차단부터 재개까지 걸린 시간, 동일 요청 재시도가 폐기된 링크를 돌려주는지, 시작 검사가
유출 키 제거를 막는지 확인하고 결과를 배포 검증 기록에 남긴다. 실제 운영 훈련 전에는 이 절차를 완료로
기록하지 않는다.
