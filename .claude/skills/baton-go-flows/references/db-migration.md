# DB 마이그레이션 점검

스키마·SQL·JPA 매핑을 바꿀 때 사용한다. 마이그레이션은 `bootstrap/src/main/resources/db/migration`에 있다.

## 파일 작성

- 적용된 `V<n>__*.sql`은 수정·삭제·이름 변경하지 않는다. 현재 가장 큰 번호 다음으로
  `V<n+1>__<영문_snake_case_설명>.sql`을 추가한다.
  번호는 `ls bootstrap/src/main/resources/db/migration`으로 확인한다.
- 절대 시각 컬럼은 `DATETIME(6)`이며 UTC 값으로 읽고 쓴다(`docs/ADR/0007_mysql-instant-storage/adr.md`).
  `TIMESTAMP`나 세션 시간대에 기대는 기본값을 쓰지 않는다. 데이터 변환에 세션 시간대가 필요하면
  V4처럼 UTC로 고정했다가 원래 값으로 복원한다.
- 해시·코드 컬럼은 기존처럼 `CHARACTER SET ascii COLLATE ascii_bin`을 사용한다.
  원문 링크 코드, 멱등성 키, HMAC 비밀값을 저장하는 컬럼을 추가하지 않는다.
- 상태 조합 규칙은 가능하면 `CHECK`·고유 제약으로 DB에도 둔다. 동시 생성·폐기·정리의 충돌은
  애플리케이션 사전 조회가 아니라 제약 위반과 잠금으로 판정한다.

## 매핑 일치

- JPA는 `ddl-auto: validate`이므로 `adapter-out-persistence`의 엔티티와 DDL의 컬럼 이름·타입·null 허용이
  어긋나면 기동이 실패한다. 엔티티, 직접 작성한 SQL, DDL을 같은 변경에서 맞춘다.
- 운영 애플리케이션 계정은 DML 전용이다. DDL은 `deploy/k8s/base/database-migration-job.yaml`의
  마이그레이션 Job만 실행한다. 런타임 코드에서 DDL이나 권한이 더 필요한 SQL을 실행하지 않는다.
- 런타임 계정 권한을 바꾸면 `deploy/k8s/base/mysql-runtime-user-init.sh`와 관련 통합 테스트를 함께 확인한다.

## 기존 데이터와 복구

- 기존 행이 있는 상태에서 새 제약·NOT NULL·변환이 실패하지 않는지 확인한다. 데이터 변환이 있으면
  `AbsoluteTimeV4MigrationIntegrationTest`·`LinkCodeKeyVersionMigrationTest`처럼 이전 버전 스키마에서
  시작하는 MySQL 통합 테스트를 추가한다.
- 복구는 DB 백업과 해당 시점의 HMAC 키 묶음을 함께 복원하는 절차다. 이미지 롤백만으로 스키마가
  되돌아가지 않으므로, 이전 이미지가 새 스키마에서 기동하는지 또는 복원이 필요한지를 RUNBOOK에 남긴다.
- 운영 적용 순서(마이그레이션 Job → 애플리케이션 롤아웃)나 잠금 시간이 바뀌면
  `docs/RUNBOOK/kubernetes-private-server-deployment.md`를 갱신한다.

## 검증

- `./gradlew --no-daemon :bootstrap:mysqlTest`는 Docker가 필요하다. 일반 `test` 성공을 MySQL 검증 성공으로
  보고하지 않는다. Docker를 쓸 수 없으면 미실행 항목과 이유를 기록한다.
- 대상 테스트를 알면 `--tests`로 좁히고, 마이그레이션 자체를 바꿨다면 `DatabaseMigrationRunnerIntegrationTest`도 포함한다.
