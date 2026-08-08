# 기존 데이터베이스 HMAC guard 최초 결합 runbook

이 절차는 `link_code_key_guard` migration을 처음 적용했지만 기존 링크 또는 생성 예약 때문에
자동 결합이 거부된 데이터베이스에만 사용한다. 신규 빈 데이터베이스는 시작 검증이 자동으로
결합하므로 이 도구를 사용하지 않는다.

도구는 canary `Idempotency-Key`의 SHA-256 예약 해시와 현재 HMAC 비밀에서 파생한 공개 코드
해시가 같은 링크를 가리키는지 DB 안에서 확인한다. 검증이 끝난 뒤에만 guard singleton의
version과 fingerprint를 같은 transaction에서 결합한다. canary 원문, 공개 코드, 코드 해시,
fingerprint와 비밀은 출력하지 않는다.

## 사전 조건

1. 모든 구버전·신버전 writer와 링크 생성 트래픽을 중지한다.
2. DB backup과 그 시점에 사용한 불변 `BATON_GO_LINK_CODE_SECRET` secret-manager version을
   하나의 복구 단위로 보존한다.
3. 실제 기존 링크 생성에 사용했고 안전하게 보관한 canary `Idempotency-Key`를 준비한다.
   추측한 값이나 새로 만든 값은 사용할 수 없다. 결합 도구는 guard 도입 전 규칙대로
   `UUID.fromString`으로 해석한 값이 입력과 대소문자만 다를 때 lowercase canonical UUID로
   정규화하므로, 과거의 uppercase, version 7, nil과 non-RFC variant canary도 사용할 수 있다.
   공개 생성 API는 계속 lowercase version `1..5` RFC variant만 허용한다.
4. V3 이상 Flyway migration이 적용되어 `link_code_key_guard` singleton이 존재하는지
   확인한다.
5. `BATON_GO_DB_URL`, `BATON_GO_DB_USERNAME`, `BATON_GO_DB_PASSWORD`,
   `BATON_GO_LINK_CODE_SECRET`을 현재 shell에 주입한다. 명령 인자나 shell history에 비밀을
   넣지 않는다.

canary나 검증된 secret version이 없으면 여기서 중단한다. 임의 비밀로 결합하거나 guard를
직접 SQL로 갱신하지 않는다.

## 검증 범위와 로그 주의

- 이 도구는 입력한 canary 한 건의 예약 hash와 링크 code hash만 검증한다. 그 성공은 모든
  과거 행이 같은 HMAC 키로 생성됐음을 증명하지 않으며 mixed-key DB를 고치지 않는다.
  키 혼합이 알려졌거나 의심되면 결합하지 말고 별도 inventory와 복구 결정을 수행한다.
- MySQL general log, audit plugin, APM query tracing과 JDBC proxy는 prepared statement의
  fingerprint·idempotency hash·code hash bind 값을 기록할 수 있다. 실행 전에 parameter
  capture를 비활성화하거나 마스킹하고 접근과 보존 기간을 제한한다. DB query나 audit log를
  운영 티켓, 채팅, CI artifact에 복사하지 않는다.

## 도구 빌드와 실행

검토·서명된 같은 release source에서 one-shot 실행 파일을 만든다.

```bash
./gradlew --no-daemon :guard-tool:bootJar
```

canary는 process argument나 export된 환경 변수가 아니라 stdin으로만 전달한다. 다음 예시는
zsh에서 입력을 화면과 history에 남기지 않는다.

```bash
(
  trap 'unset BATON_GO_CANARY_KEY BATON_GO_GUARD_STATUS' EXIT
  read -rs "BATON_GO_CANARY_KEY?Canary Idempotency-Key: "
  printf '%s\n' "$BATON_GO_CANARY_KEY" |
    java -jar guard-tool/build/libs/baton-go-guard-binding.jar --confirm-writers-stopped
  BATON_GO_GUARD_STATUS=$?
  exit "$BATON_GO_GUARD_STATUS"
)
```

subshell의 종료 코드는 cleanup 뒤에도 Java 종료 코드를 그대로 유지한다. 일반
`baton-go.jar`에 결합 확인 인자를 잘못 전달하면 Spring을 시작하지 않고 거부하므로, 반드시
별도 `baton-go-guard-binding.jar` artifact인지 확인한다.

- 성공: 새 결합 또는 같은 identity의 기존 결합을 안전한 문장으로 알리고 exit `0`
- 사용법 오류: exit `2`
- 설정·canary·DB 상태 검증 실패: 민감한 세부 정보 없이 exit `3`

실패 시 DB transaction은 rollback된다. 원인을 확인하려고 fingerprint, canary, 해시나 JDBC
credential을 로그·티켓·CI artifact에 붙이지 않는다.

네트워크 단절 등으로 commit 성공 여부와 exit 결과가 불명확하면 rollback을 가정하지 않는다.
writer를 계속 중지하고 같은 secret version과 같은 canary로 동일 도구를 다시 실행한다.
첫 실행이 이미 commit됐다면 도구는 canary와 identity를 다시 검증한 뒤 exit `0`으로
멱등 성공한다. 결과가 불명확하다는 이유로 canary나 secret을 바꾸거나 직접 SQL을 실행하지
않는다.

## 결합 후 검증

1. 같은 release binary를 정상 모드로 시작한다.
2. readiness가 열리기 전에 HMAC guard 시작 검증이 통과하는지 확인한다.
3. 보관한 canary payload를 같은 키로 재생해 기존 link ID와 short URL로 수렴하는지 확인한다.
   응답의 전체 short URL은 로그나 증거 문서에 복사하지 않는다.
4. 신규 canonical intent 한 건의 생성·재생과 공개 resolver를 smoke test한다.
5. 검증이 끝난 뒤에만 writer와 생성 트래픽을 다시 연다.

DB와 secret을 함께 복구하는 훈련에서도 같은 검증 순서를 사용한다. key ring을 도입하기
전에는 성공적으로 결합한 DB의 secret을 회전하지 않는다.
