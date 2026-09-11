# 2026-09-08 구현·검증 기록

GO `bc241e2`의 HANDOFF에 있던 완료 내역과 이전 검증 기록을 옮겼다.
아래 상태와 미실행 항목은 각 기록의 확인 시점 기준이다. 현재 상태와 다음 작업은
[HANDOFF](../../HANDOFF.md)를 확인한다.

## GO 구현·검증 기록

- BATON GO의 링크 생성·조회·접속 처리·폐기, 동일 요청 재시도와 v1 링크 대상 규칙은 구현되어 있다.
  제품 동작은 [제품 기준](../../docs/PRD/0001_product-baseline/spec.md), HTTP 동작은
  [API 계약](../../docs/PRD/0002_api-contract/spec.md)을 기준으로 삼는다.
- 관리 조회·폐기는 단축 링크 접속 처리와 같은 시간·폐기 정책의 이용 상태와 판정 시각을 반환한다.
  생성·재생 응답과 저장 스키마는 유지하며 BATON·ROUND 접근 권한을 나타내지 않는다.
- 일반 관리 목록은 기존 읽기 scope로 대상 시스템·생성 기간·이용 상태를 검색한다.
  검사량을 제한하고 필터로 빈 페이지가 되어도 다음 커서로 진행하며, 비허용 저장 대상은 제외한다.
- 공개 링크의 HTML 안내는 GO의 요청 제한·서버 오류도 포함한다. JSON 클라이언트와 관리
  인증 오류는 기존 형식을 유지하며, 요청 제한 화면의 대기 시간은 `Retry-After`와 같다.
- BATON·ROUND 위치 식별자와 최종 접근 권한 확인은
  [교차 서비스 링크 계약](../../docs/PRD/0003_cross-service-link-contract/spec.md)을 따른다.
  GO 링크는 위치만 제공하며 BATON 접근 권한이나 ROUND 입장 권한을 부여하지 않는다.
- 링크 코드 HMAC 보호 장치, 재시도 시 최초 공개 출처 유지와 MySQL 절대 시각 저장 결정은
  각각 [ADR-0004](../../docs/ADR/0004_link-code-key-binding/adr.md),
  [ADR-0009](../../docs/ADR/0009_idempotent-public-origin-replay/adr.md),
  [ADR-0007](../../docs/ADR/0007_mysql-instant-storage/adr.md)을 따른다.
- V7는 자동 삭제 표시와 요청 내용 해시를 추가한다. 종료 링크 자동 정리는 기본 중지하며
  [보존 절차](../../docs/RUNBOOK/link-retention.md)에 따라 기간을 명시해야 활성화된다.
- V6는 기존 예약을 `legacy` 키로 이관하고 새 예약에 발급 키 ID를 저장한다. 키 교체와
  이전 키 제거 조건은 [ADR-0011](../../docs/ADR/0011_link-code-key-ring/adr.md)을 따른다.
  실제 운영 키 묶음 배포·전환·복원 훈련은 [운영 절차](../../docs/RUNBOOK/link-code-key-rotation.md)에 따라 남아 있다.
- 계약 전 저장 데이터 조사·폐기 API는 구현되어 있지만 기본 비활성화 상태다. 공개 링크 접속 차단을
  확인한 유지 보수 시간에만 두 활성화 값을 함께 사용하며, 일반 운영에서는 모두 `false`로 둔다.
- Redis 분산 요청 제한은 기본 중지하며 [분산 요청 제한 절차](../../docs/RUNBOOK/distributed-public-rate-limit.md)에
  따라 운영 Redis를 연결해야 한다. 두 연결 동시성·실패 차단과 공개 HTTP 검증은 준비되어 있다.
- GO 전용 MySQL과 비공개 Kubernetes 기본 구성은 준비되어 있다. 이는 배포 기반일 뿐 실제
  클러스터 검증이나 공개 운영 승인 기록이 아니다. MySQL 워크로드는 비루트 UID/GID와 Linux
  capability 제거를 선언하지만, Namespace의 `restricted` 정책 강제 적용 전에는 고정 이미지로 신규·
  복원·현재 PVC의 기동과 TLS·초기화·마이그레이션을 실제 클러스터에서 검증해야 한다.
- 공개·관리 응답 지연과 전체·관리 API 5xx·관리 JWT 서비스 장애·관리 링크 복구 오류·429·저장 대상 계약 위반의 [Prometheus 경보 규칙](../../deploy/prometheus/baton-go-alerts.yml)과
  `promtool` 검증은 준비되어 있다. 기존 Prometheus·Alertmanager를 재사용하는
  [수집·알림 연결 절차](../../docs/RUNBOOK/prometheus-alerts.md#추가-서비스-요금-없는-연결), Pod 자동 발견 설정과
  Namespace 범위 조회 권한을 추가했다. 특정 Pod 수집 실패·전체 대상 누락도 감지하며 새 서버나
  유료 서비스는 추가하지 않는다. 실제 수집기·알림 경로 연결과 인프라·백업 경보는
  [경보 운영 절차](../../docs/RUNBOOK/prometheus-alerts.md)에 따라 운영 환경에서 검증해야 한다.
- 2026-09-07 모니터링 검증 기준은 `663cd84`이며 검증 대상의 미커밋 변경은 없다.
  `promtool check config --syntax-only`, `check rules`, `test rules`로 수집 예시·12개 경보 규칙과
  기존·수집 장애 테스트 두 묶음을 통과했다. `kubectl kustomize`·Kubeconform strict로 조회 권한
  2개, actionlint로 CI를 검증했다. `amtool check-config`·`config routes test`는 문서 예시를 임시
  설정에 병합해 GO·다른 서비스의 수신자 분리를 확인했다. 규칙·스키마·CI 로그는
  `/private/tmp/baton-go-free-monitoring-{rules,schema,ci}.log`에 있다.
  Java·DB 변경이 없어 해당 테스트는 생략했다. 운영 Kubernetes context와 수신자 설정을 확인할 수
  없어 실제 Pod 발견·네트워크 접근·배포·알림 발송은 미실행이다.
- CI는 검사한 커밋·이미지 구성 다이제스트·아카이브 체크섬과 SBOM·취약점 보고서를
  `baton-go-image-security`로 보존한다. 취약점 등급별 자동 차단이나 릴리스 이미지 승인 기록은
  아니며, [이미지 검사 절차](../../docs/RUNBOOK/image-security-reports.md)에 따라 결과를 검토한다.
- 대표 HTTP 요청·응답은 기존 계약 테스트에서 REST Docs 조각으로 생성하며
  `:adapter-in-web:apiContractDocs`가 압축 산출물을 만들고 CI가 `baton-go-rest-docs`로
  보존한다. 보존 기간은 [CI 워크플로](../../.github/workflows/ci.yml)의 `retention-days` 설정을
  따른다. 제품 동작과 전체 오류 조건의 기준 문서는
  [API 계약](../../docs/PRD/0002_api-contract/spec.md)이다.
- 관리 쓰기 완료 이력은 JWT `sub`와 내부 링크·요청 ID만 사용하며 기존 중앙 로그 보존 정책을
  따른다. 별도 감사 DB는 없으며, 실제 수집·조회 권한과 보존 정책 확인은
  [관리 작업 이력 운영 절차](../../docs/RUNBOOK/management-operation-history.md)에 따라 운영 환경에서 수행한다.

## BATON·ROUND 구현·검증 기록

- 2026-08-27 BATON `2ddfed0bd6fbd5c4a4e1595110d3b46bb6ab32dd`에서 계정 세션,
  구성원 연결, ROUND 방 매핑·참여권·JWK와 선택 실행 조건 검증 구현을 확인했다.
- 2026-08-27 ROUND `a67df4ba89e935b623d58f2ce7542a5dbbbaea7f`에서 BATON 모드
  브라우저 진입, 참여권 검증, TURN·WebSocket 방 접근 제한 구현을 확인했다.
- 위 커밋 확인은 실제 릴리스 이미지, 공개 HTTPS, 외부 coturn과 운영 자격 증명 검증을
  대신하지 않는다.
- 2026-09-05 BATON의 `codex/go-link-integration-20260905` 브랜치 `e5685e6f`에 ROUND 방의
  GO 생성·폐기 전달, 인증된 조회·화면 복사, 운영 조회·재처리와 이전 공개 도메인 재생을 구현했다.
  작업 공간은 `/private/tmp/baton-go-integration-20260905`이며 원본 `c89927d0`의 오늘 화면
  자료 재확인 기능까지 통합했다.
- 2026-09-07 같은 BATON 브랜치 `2852bd95`에 카카오톡 공유와 QR 표시·PNG 저장을 추가했다.
  코드 검증 기준은 `571f508b`이며 검증 대상의 미커밋 변경은 없다. QR은 브라우저에서 생성하고
  카카오 공유는 공개 JavaScript 키를 설정해야 표시한다. 추가 요금이 없도록 카카오 앱의 유료 API
  사용을 끄는 절차를 문서화했다. 키 미등록 상태로 실제 메시지 전송·무료 사용 설정·실기기 스캔은 미검증이다.
  프런트엔드 타입 검사·빌드와 공유 관련 Chromium·390px 모바일·WebKit 12개 시나리오가 통과했다.
  변경한 배포 스크립트 문법·ShellCheck·기존 사전점검, Compose의 공개 키 전달과 Caddy CSP도 확인했다.
  당시 명령·결과·로그는 BATON 작업 공간의 `output/verification/go-sharing-initial.md`에 있다.
  GO 서버·API·DB 변경이 없어 해당 검증은 반복하지 않았다. 실제 배포와 원격 푸시는 하지 않았다.
- GO 직접 폐기의 백그라운드 반영, 전달 중지·2분 지연 안내, 기본 5분 전달 대기·15분 상태 확인
  지연 경보를 제공한다. 마지막 정상 확인 시각과 실패 코드를 따로 보관하고 확인 실패만으로
  저장된 링크를 숨기지 않는다. 생성·폐기 및 상태 확인 모두 각각 기본 최대 20건·5초의 묶음으로
  처리한다. 폐기를 먼저 고르고 원격 상태 확인 실패 뒤에도 묶음의 다음 링크를 확인한다.
  전송 이력이 없는 요청의 원본 종료를 발견하면 외부 호출 없이 취소를 완료하며, 이 정리도
  처리 건수에 포함하고 같은 묶음의 다음 요청을 계속 처리한다.
- GO 설정은 기존 BATON 운영 환경 검증·사전점검·배포 도구에 연결했다. 링크 생성 요청 저장만
  켜면 JWT가 필요 없고 GO API 호출을 켜면 JWT 디렉터리를 자동 연결한다. 전용 디렉터리 0750·파일 0640과 동일한
  root 이외 읽기 그룹을 확인한 뒤 앱에 그 그룹만 추가한다. 파일 교체 시에도 같은 권한을 유지한다.
  GO JWT 누락·권한·형식 오류 중에도 기존 컨테이너 중지·진단과 고정 MySQL 명령을 실행할 수 있다.
  기동·재배포·일반 앱 명령·사전점검은 JWT 검사를 유지하고, 기존 운영 잠금과 다른 자격 증명 검사도 보존한다.
- 운영 도구는 장애 복구 뒤 `CREATE_UNKNOWN`·`REVOKE_PENDING`도 조회한 상태·시도 횟수·오류가
  같고 선점 기록이 없으면 즉시 처리 대기로 변경한다. 이미 처리 시각이 되었거나 같은 명령을 반복하면
  변경하지 않는다. 요청 UUID·원격 링크 ID·취소 표식은 보존하고 외부 호출은 전달 작업자가 수행한다.
- BATON 품질 게이트에 지정한 GO 버전으로 연동 테스트하는 `go_contract`를 추가했다. 검증 커밋은
  `contracts/baton-go/commit.txt`의 `b4b4df22cbabf7a71697d60db5294ecfeb857e86`이다.
  `/private/tmp/baton-go-contract-source-20260905`의 깨끗한 고정 소스로 CI와 같은 스크립트를
  실행해 성공을 확인했으며, 다른 커밋을 지정하면 빌드 전에 중단한다. 실패 보고서에는 테스트
  결과와 GO 로그만 보존한다. 테스트 JWT 파일은 업로드하지 않는다.
- GitHub에서 이 검증을 실행하려면 고정 GO 커밋의 원격 반영과 BATON Actions secret
  `BATON_GO_CONTRACT_READ_TOKEN`의 GO 전용 읽기 자격 증명 등록이 필요하다. 확인 당시 GO 원격
  `main`은 `f1aab216`이며 필요한 최신 관리 상태 계약이 없다. 자격 증명을 생략하면 품질 게이트가
  실패하도록 연결했고, 실제 GitHub 실행·원격 게시·자격 증명 등록은 수행하지 않았다.
- 자료 재확인 기능 병합 뒤 GO 전달·자료 권한의 DB 검증, API 계약, 실제 서명 JWT·두 MySQL의
  GO 연동 검증과 프런트엔드 빌드·관련 화면 14건을 통과했다. 즉시 재시도의 기존 식별자·취소 보존과
  반복 실행 거부도 DB에서 확인했다. 운영 사전점검, 스크립트 27개 문법·정적 분석과 CI 설정 검사를 통과했다.
  앞선 검증에서는 실제 비루트 컨테이너의 JWT 읽기·원자 교체 반영·쓰기 차단을 확인했다.
- 최신 BATON `main`과 GO 연동 브랜치의 병합은 남아 있다. 2026-09-07 원본 BATON 작업 공간은
  `codex/free-holiday-integration`의 `1ee443e4`이며 미커밋 변경이 없다. 병합 시 GO 연동과 이후
  BATON·공휴일 기능을 함께 유지하고 충돌한 동작만 다시 검증한다.
  미배포 GO DB 변경 V40~V42는 통합한 계정 비활성화 V39 다음 순서다.
  운영 DB에 이미 적용한 마이그레이션 파일을 교체하는 절차가 아니다.
- 실제 운영 발급자·HTTPS·JWT 읽기 scope와 설정 활성화, 점검 실패의 알림 경로 연결 및 부하
  확인은 남아 있다. 상태 확인·링크 생성 요청 저장·GO API 호출은 기본 중지하며 기존 방 일괄 생성은 포함하지 않는다.
