# 링크 코드 키 교체

## 설정

기존 `BATON_GO_LINK_CODE_SECRET`은 `legacy` 키로 유지된다. 새 구성은 Spring 표준 환경 변수
바인딩을 사용하므로 속성 이름의 하이픈은 제거하고 점만 밑줄로 바꾼다.
키 ID는 소문자·숫자로 지정하고 환경 변수 이름에서만 대문자로 쓴다.

```text
BATONGO_LINKCODE_ACTIVEKEYID=k202609
BATONGO_LINKCODE_KEYS_LEGACY=<기존 비밀값>
BATONGO_LINKCODE_KEYS_K202609=<새 비밀값>
```

`BATON_GO_LINK_CODE_SECRET`과 `BATONGO_LINKCODE_KEYS_LEGACY`를 동시에 설정하지 않는다.
모든 키는 32자 이상이며 비밀값을 정규화하거나 공백을 제거하지 않는다. 한 키 ID의 값을
바꾸면 시작을 거부한다. 원문 값이나 지문을 배포 로그·명령 인자·Git에 넣지 않는다.

Kubernetes 기본 배포는 선택적인 `baton-go-link-code-key-ring` Secret의 위 환경 변수를 읽는다.
전체 키 묶음을 이 Secret으로 옮길 때는 기존 `baton-go-link-code-secret`에 대한 참조가
동시에 `legacy`를 주입하지 않도록 기존 Secret의 처리와 Pod 교체를 함께 수행한다.
Secret 생성은 환경의 비밀값 관리 절차를 사용하며 저장소에 실제 값을 적은 매니페스트를 만들지 않는다.

Compose는 다음 오버레이로 소유자만 읽을 수 있는 별도 dotenv 파일을 주입한다.
이 파일에는 위 키 묶음 변수만 두고 셸에서 `source`하지 않는다.

```bash
docker compose --env-file .env -f compose.yml -f compose.key-ring.yml config --quiet
docker compose --env-file .env -f compose.yml -f compose.key-ring.yml up -d app
```

`BATON_GO_LINK_CODE_KEY_RING_ENV_FILE`은 해당 파일의 절대 경로다. 오버레이는 기존 단일
비밀값 환경 변수를 비워 중복 설정을 막는다. 키가 전혀 없으면 애플리케이션 시작이 실패한다.

## 일반 교체 순서

1. V6를 적용하고 DB와 현재 키 묶음의 복구본을 함께 확보한다.
2. 현재 발급 키를 유지한 채 새 ID·비밀값을 모든 Pod에 추가한다. 각 Pod 재시작과 준비 상태를 확인한다.
3. `BATONGO_LINKCODE_ACTIVEKEYID`를 새 ID로 바꾸고 Pod를 순차 교체한다. 두 키가 모두 있는
   동안 발급 키가 다른 Pod도 생성 예약에 저장된 키 ID로 기존 URL을 반환한다.
4. 기존 요청을 재시도하면 기존 URL을 반환하는지 확인한다. 새 요청의 생성·재시도·리다이렉트·폐기도 검증한다.
5. 이전 키로 링크를 발급하는 Pod가 모두 종료되고 [보존 정리](link-retention.md)로 해당 키를 사용하는
   미정리 생성 예약이 없어졌을 때만 비밀값을 실행 설정에서 제거한다.
   등록 키 메타데이터는 남기며 같은 키 ID에 다른 값을 다시 할당하지 않는다.

첫 지표 수집 전 오류와 Pod 기동 실패는 시작 로그·Pod 경보로 확인한다. 실행 중 기존 URL 복원 오류는
`BatonGoManagementLinkRecoveryFailure`와 요청 ID를 사용한다. 유출 사고에서 키 교체만으로
이전 링크가 무효화된다고 판단하지 않는다. 원래 링크의 폐기·재발급과 대상 서비스 권한
검증을 함께 수행한다.

실제 환경의 Secret 교체와 복원 훈련을 완료하기 전에는 운영 키 교체가 완료되었다고 기록하지 않는다.
