# 기존 DB의 HMAC 키 정보 최초 등록 절차

이 절차는 `link_code_key_guard` 마이그레이션을 처음 적용했지만 기존 링크 또는 생성 예약 때문에
자동 등록이 거부된 데이터베이스에만 사용한다. 신규 빈 데이터베이스는 시작 검증이 키 정보를
자동 등록하므로 이 도구를 사용하지 않는다.

도구는 검증용 `Idempotency-Key`의 SHA-256 예약 해시와 현재 HMAC 비밀값에서 파생한 공개 코드
해시가 같은 링크를 가리키는지 DB 안에서 확인한다. 검증이 끝난 뒤에만 보호 정보 단일 행의
버전과 지문을 같은 트랜잭션에서 등록한다. 검증용 키 원문, 공개 코드, 코드 해시,
지문과 비밀값은 출력하지 않는다.

## 사전 조건

1. 모든 버전의 DB 쓰기 프로세스와 링크 생성 트래픽을 중지한다.
2. DB 백업과 그 시점에 사용한 불변 `BATON_GO_LINK_CODE_SECRET` 비밀값 관리자 버전을
   하나의 복구 단위로 보존한다.
3. 기존 링크 생성에 사용하고 별도로 보관한 검증용 `Idempotency-Key`를 준비한다.
   추측한 값이나 새로 만든 값은 사용할 수 없다. 등록 도구는 보호 정보 도입 전 규칙대로
   `UUID.fromString`으로 해석한 값이 입력과 대소문자만 다를 때 소문자 정규 UUID로
   정규화하므로, 과거의 대문자, 버전 7, nil과 비 RFC 변형 검증용 키도 사용할 수 있다.
   공개 생성 API는 계속 소문자 버전 `1..5` RFC 변형만 허용한다.
4. V3 이상 Flyway 마이그레이션이 적용되어 `link_code_key_guard` 단일 행이 존재하는지
   확인한다.
5. `BATON_GO_DB_URL`, `BATON_GO_DB_USERNAME`, `BATON_GO_DB_PASSWORD`,
   `BATON_GO_LINK_CODE_SECRET`을 현재 셸에 주입한다. 명령 인자나 셸 기록에 비밀값을
   넣지 않는다.

운영 DB 접속은 [배포 절차의 TLS 신뢰 저장소 계약](kubernetes-private-server-deployment.md#3-namespace와-secret-준비)을
따른다. CLI 실행 환경에서도 JDBC 호스트가 해석되고 URL에 지정한 공개 CA 저장소를 읽을 수
있어야 한다. `sslMode=VERIFY_IDENTITY`, `trustCertificateKeyStoreType=PKCS12`,
`fallbackToSystemTrustStore=false`를 유지한다. CLI는 애플리케이션과 같은 공개 CA 저장소
비밀번호 `baton-go-public-ca-v1`을 JDBC `Properties`로 전달하므로 URL에 중복 지정하지 않는다.
이 저장소에는 개인 키나 다른 자격 증명을 넣지 않는다. CLI는 Spring을 시작하지 않으므로
`application.yml`과 `spring.datasource.hikari.*` 설정은 읽지 않는다.
연결·응답 대기는 Connector/J 표준 속성으로 제한한다. 기본값과 제한 범위는
[DB 대기 시간](kubernetes-private-server-deployment.md#db-대기-시간)을 따른다.

검증용 키나 검증된 비밀값 버전이 없으면 여기서 중단한다. 임의 비밀값을 등록하거나 보호 정보를
직접 SQL로 갱신하지 않는다.

비밀값 유출·오용이 의심되는 사고에는 이 도구를 사용하지 않는다. `guard-tool`은
검증된 기존 HMAC 키 정보를 DB에 최초 등록하는 도구다. 키 교체나 혼합 키 데이터 복구는 지원하지 않는다.
사고 시에는 [ADR-0004의 비밀값 유출 대응](../ADR/0004_link-code-key-binding/adr.md#비밀값-유출-대응)을
따라 링크 생성과 공개 링크 접속을 차단하고 복구 방식을 먼저 결정한다.

## 검증 범위와 로그 주의

- 이 도구는 입력한 검증용 키 한 건의 예약 해시와 링크 코드 해시만 검증한다. 그 성공은 모든
  과거 행이 같은 HMAC 키로 생성됐음을 증명하지 않으며 혼합 키 DB를 고치지 않는다.
  키 혼합이 알려졌거나 의심되면 등록하지 말고 별도 데이터 점검과 복구 결정을 수행한다.
- MySQL 일반 로그, 감사 플러그인, APM 질의 추적과 JDBC 프록시는 준비된 문장
  (prepared statement)의 지문·멱등성 해시·코드 해시 바인딩 값을 기록할 수 있다. 실행 전에 매개변수
  수집을 비활성화하거나 마스킹하고 접근과 보존 기간을 제한한다. DB 질의나 감사 로그를
  운영 티켓, 채팅, CI 산출물에 복사하지 않는다.

## 도구 빌드와 실행

검토·서명한 배포 원본에서 일회성 CLI JAR을 만든다.

```bash
./gradlew --no-daemon :guard-tool:bootJar
```

검증용 키는 프로세스 인자나 내보낸 환경 변수가 아니라 표준 입력으로만 전달한다. 다음 예시는
zsh에서 입력을 화면과 기록에 남기지 않는다.

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

하위 셸의 종료 코드는 정리 뒤에도 Java 종료 코드를 그대로 유지한다. 일반
`baton-go.jar`에 등록 확인 인자를 잘못 전달하면 Spring을 시작하지 않고 거부하므로, 반드시
별도 `baton-go-guard-binding.jar` 파일인지 확인한다.

- 성공: 비밀값 없이 신규 등록 또는 기존 등록 확인 결과를 출력하고 종료 코드 `0`
- 사용법 오류: 종료 코드 `2`
- 설정·검증용 키·DB 상태 검증 실패 또는 DB 통신 시간 초과: 민감한 세부 정보 없이 종료 코드 `3`

트랜잭션 처리 중 실패하면 롤백을 시도한다. 원인을 확인하려고 지문, 검증용 키, 해시나 JDBC
자격 증명을 로그·티켓·CI 산출물에 붙이지 않는다.

네트워크 단절·시간 초과 등으로 커밋 성공 여부와 종료 결과가 불명확하면 롤백을 가정하지 않는다.
DB 쓰기 프로세스를 계속 중지하고 같은 비밀값 버전과 같은 검증용 키로 동일 도구를 다시 실행한다.
첫 실행이 이미 커밋됐다면 도구는 검증용 키와 식별 정보를 다시 검증한 뒤 종료 코드 `0`으로
멱등 성공한다. 결과가 불명확하다는 이유로 검증용 키나 비밀값을 바꾸거나 직접 SQL을 실행하지
않는다.

## 등록 후 검증

1. 같은 배포 이미지를 정상 모드로 시작한다.
2. 준비 상태가 열리기 전에 HMAC 키 등록 검사가 통과하는지 확인한다.
3. 보관한 검증용 요청을 같은 키와 내용으로 재시도해 기존 링크 ID와 단축 URL을 반환하는지 확인한다.
   응답의 전체 단축 URL은 로그나 검증 기록에 복사하지 않는다.
4. 새 정규 UUID로 링크 한 건을 생성하고 같은 요청의 재시도와 리다이렉트를 스모크 테스트한다.
5. 검증이 끝난 뒤에만 DB 쓰기 프로세스와 생성 트래픽을 다시 연다.

DB와 비밀값을 함께 복구하는 훈련에서도 같은 검증 순서를 사용한다. 키 묶음을 도입하기
전에는 키 정보를 등록한 DB의 비밀값을 회전하지 않는다.
