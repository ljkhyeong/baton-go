# 인수인계

## 현재 상태

- GO의 링크 생성·조회·접속 처리·폐기와 동일 요청 재시도는 구현되어 있다.
  동작 기준은 [제품 명세](docs/PRD/0001_product-baseline/spec.md),
  [API 계약](docs/PRD/0002_api-contract/spec.md),
  [BATON·ROUND 연동 계약](docs/PRD/0003_cross-service-link-contract/spec.md)이다.
  GO 링크는 위치만 제공하며 접근 권한은 BATON·ROUND가 판단한다.
- 종료 링크 자동 삭제, Redis 분산 요청 제한, 대상 계약 점검·폐기 API는 기본 중지 상태다.
  실제 운영 환경 검증과 배포는 남아 있다.
- 일반 링크 폐기 완료 이력은 최초 요청을 `LINK_REVOKE`, 이미 폐기된 링크의 반복 요청을
  `LINK_REVOKE_REPLAY`로 구분한다. HTTP 응답과 폐기 시각 보존 동작은 바뀌지 않았다.
- 관리 JWT는 공백이 아닌 `sub`를 서비스 식별자로 요구한다. 누락·빈 문자열·공백 문자열은
  관리 작업 전에 `401 MANAGEMENT_AUTHENTICATION_REQUIRED`로 거부한다.
- 링크 생성 예약은 일반 `INSERT`를 사용하며 Spring이 변환한 중복 키 오류만 기존 예약 조회로
  처리한다. 다른 MySQL 저장 오류는 성공이나 재시도로 바꾸지 않는다.
- 멱등 예약의 공개 출처 복구는 저장값 누락과 잘못된 URL 형식만
  `PUBLIC_LINK_ORIGIN_REPLAY_UNAVAILABLE`로 처리한다. 그 밖의 실행 오류는 복구 오류로 바꾸지 않는다.
- 종료 링크 정리와 Redis 분산 요청 제한의 수치·시간 범위는 Spring `@ConfigurationProperties`
  검증을 사용한다. 기능 활성화에 따른 필수값 조건만 생성자에서 확인한다.
- 준비 상태는 DB와 활성화한 분산 요청 제한 Redis를 확인한다. Redis `PING`이 실패하면
  `/readyz`는 503이며 `/livez`는 정상 상태를 유지한다.
- `BatonGoReadinessFailed`는 Pod별 `/readyz` 요청이 최근 2분간 12건 이상이고 503 비율이
  50%를 넘는 상태가 2분 지속되면 발생한다.
- BATON 연동 작업 공간은 `/private/tmp/baton-go-integration-20260905`, 브랜치는
  `codex/go-link-integration-20260905`다. 최근 확인 리비전은 `90557822`, 코드 기준은 `bc888b15`다.
  GO 연동·카카오톡 공유·QR 기능을 반영했으며 BATON 메인 병합과 실제 전송·스캔 검증은 남아 있다.
- 완료 내역과 이전 검증의 리비전·명령·로그는
  [구현·검증 기록](docs/HISTORY/2026-09-08-implementation-verification.md)에 보관한다.

## 최근 검증

- 2026-09-12 `99d24a7`에서 Pod별 준비 상태 지속 실패 경보를 추가했다. 규칙 문법 검사와
  정상·간헐 실패·지속 실패·복구 시나리오를 포함한 전체 `promtool` 규칙 테스트가 성공했다.
  애플리케이션 코드는 바뀌지 않아 Gradle 테스트는 반복하지 않았다.
- 2026-09-12 `4beed9d`에서 분산 요청 제한 Redis 상태 확인을 준비 상태에 추가했다.
  상태 확인 대상 테스트 3건, 실제 Redis 통합 테스트 3건과 `./gradlew --no-daemon test`가
  성공했다. MySQL 동작은 바뀌지 않아 MySQL 통합 테스트는 반복하지 않았다.
- 2026-09-12 `e4c2c0a`에서 공개 출처 복구의 광범위한 `RuntimeException` 처리를 제거했다.
  애플리케이션 대상 테스트, 공개 출처가 없는 기존 MySQL 예약 통합 테스트와
  `./gradlew --no-daemon test`가 성공했다. Redis 동작은 바뀌지 않아 Redis 통합 테스트는 반복하지 않았다.
- 2026-09-12 `19a2823`에서 종료 링크 정리와 Redis 분산 요청 제한의 직접 범위 비교를
  `@Min`·`@Max`·`@DurationMin`·`@DurationMax`로 교체했다. 설정 경계 13건, 실제 Redis 제한
  동작 2건과 `./gradlew --no-daemon test`가 성공했다. DB 동작은 바뀌지 않아 MySQL 통합
  테스트는 반복하지 않았다.
- 2026-09-12 `41cd625`에서 링크 생성 예약의 `INSERT IGNORE`를 제거했다. 변경 후 같은 요청의
  동시 생성·최초 트랜잭션 롤백·서버별 공개 출처 차이 3건과 비중복 MySQL 저장 오류 전파 1건이
  통과했다. `./gradlew --no-daemon test`도 성공했으며 Redis 동작은 바뀌지 않아 Redis 통합
  테스트는 반복하지 않았다.
- 2026-09-12 `bf93553`에서 Spring `JwtClaimValidator`로 관리 JWT의 `sub`를 필수화했다.
  실제 서명 JWT의 `sub` 누락·빈 문자열·공백 거부와 기존 유효 토큰을 대상 테스트 7건으로
  확인했다. `./gradlew --no-daemon test`도 성공했다. DB·Redis 동작은 바뀌지 않아 태그 통합
  테스트는 반복하지 않았다.
- 2026-09-12 `349ec9f`에서 일반 링크의 최초 폐기와 반복 폐기 완료 이력을 구분했다.
  `./gradlew --no-daemon test`가 성공해 애플리케이션·웹·부트스트랩 등 일반 테스트를 모두 통과했다.
  이에 앞서 애플리케이션과 웹 대상 테스트를 동시에 실행했을 때 같은 Gradle 출력 디렉터리 경합으로
  애플리케이션 컴파일 한 번이 실패했다. 웹 계약 테스트 41개는 통과했고, 경합이 끝난 뒤
  애플리케이션 서비스 테스트 20개를 다시 실행해 모두 통과했다. MySQL·Redis 동작은 바뀌지 않아
  태그 통합 테스트는 반복하지 않았다.
- 2026-09-08 검증 기준은 GO `85f6189`다. `8c3d062`의 미커밋 변경이 없는 상태에서 시작했다.
  검증한 코드·테스트 11개 파일을 커밋했고 이후에는 계약 문서와 인계 기록만 수정했다.
- `GET /api/v1/links/batch?linkIds=...`를 추가했다. 최대 100개 ID를 한 번의 DB 조회로 읽고,
  요청 순서·중복 제거·동일 판정 시각을 보장한다. 없거나 허용되지 않은 링크는 `notFoundIds`로 반환한다.
  기존 읽기 scope와 대상 정책을 사용하고, 목록 조회와 대상 검사·결과 변환을 공유한다.
- Java 21(`/Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home`)과 Docker로
  애플리케이션 46개·웹 124개·MySQL 43개를 확인했다. 최종 실패·제외는 없다.
  REST Docs 14개 예시와 목록·일괄 조회 예시의 판정 시각 일치, 변경 문서의 로컬 링크 42개도 확인했다.

```bash
./gradlew --no-daemon :application:test :adapter-in-web:apiContractDocs :bootstrap:mysqlTest
./gradlew --no-daemon :adapter-in-web:test --tests '*LinkManagementHttpContractTest' \
  :adapter-in-web:apiContractDocs :bootstrap:mysqlTest
./gradlew --no-daemon :adapter-in-web:test --tests '*LinkManagementHttpContractTest' \
  :adapter-in-web:apiContractDocs
```

- 최초 실행은 애플리케이션 46개·웹 123개 통과 후 새 HTTP 테스트의 예시 시각 불일치 1건으로 중단했다.
  예시를 고친 뒤 해당 클래스 40개와 미실행 MySQL 43개를 통과했다. 기존 목록 예시의 시각도 맞춘 뒤
  HTTP 40개와 문서 생성만 다시 실행했다. 변경되지 않은 테스트의 성공 결과는 재사용했다.
- 위 명령 순서의 로그는 `/private/tmp/baton-go-batch-lookup-tests-20260908.log`,
  `/private/tmp/baton-go-batch-lookup-final-tests-20260908.log`,
  `/private/tmp/baton-go-batch-lookup-docs-tests-20260908.log`다. 합산 결과와 예시 확인은
  `/private/tmp/baton-go-batch-lookup-verification-20260908.json`에 있다.
- Redis 변경이 없어 통합 검증은 반복하지 않았다. 운영 배포와 BATON 호출부의 일괄 조회 전환은 남아 있다.
- BATON `codex/go-link-integration-20260905`의 `90557822`에 공유 문구와 검증 기록을 반영했다.
  코드 기준은 `bc888b15`다. 타입·빌드 검사와 공유 시나리오 12개를 통과하고, 안내 위치를 맞춘 뒤
  직접 복사 3개만 추가 검증했다. 명령·포트 조건·로그는
  `/private/tmp/baton-go-integration-20260905/output/verification/latest.md`에 있다.
- 실제 카카오 전송과 BATON 메인 병합은 남아 있다.

## 다음 작업

1. [대상 계약 v1 점검·폐기 절차](docs/RUNBOOK/target-contract-v1-remediation.md)에 따라
   배포 DB의 모든 링크를 점검하고 `unrevoked non-compliant=0`, 승인되지 않은 `HOLD=0` 결과를 기록한다.
2. 실제 릴리스 이미지에서 BATON·ROUND 인증, 공개 HTTPS와 외부 coturn을 연결한다.
   세션·CSRF·쿠키·JWK 교체·TURN·WebSocket 검증 결과를 기록한다. GO 관리 API는
   Spring Security JWT와 작업별 scope로 전환했으므로 실제 발급자 식별자·JWK, 서비스 신원,
   audience·scope와 키 교체를 비공개 네트워크에서 검증한다.
3. 최신 BATON 변경과 GO 연동 브랜치를 병합하고 충돌한 동작을 검증한다.
   GO 연동과 이후 BATON·공휴일 기능을 함께 유지한다. 아직 배포하지 않은 GO 마이그레이션 V40~V42는
   계정 비활성화 V39 다음 순서이며, 운영 DB에 적용한 파일은 교체하지 않는다.
   고정 GO 커밋 `b4b4df22cbabf7a71697d60db5294ecfeb857e86`의 원격 반영과 BATON Actions의
   `BATON_GO_CONTRACT_READ_TOKEN` 등록을 확인한 뒤 GitHub 품질 게이트를 실행한다.
   실제 서비스 JWT와 HTTPS 환경에서 생성 응답 유실 후 취소·폐기까지 재시도되는지 확인한다.
   상태 확인 장애 경보와 링크 생성·폐기·상태 일괄 조회도 검증한다.
   상태 확인·링크 생성 요청 저장·GO API 호출은 기본 중지하며 기존 방의 링크 일괄 생성은 포함하지 않는다.
   카카오 앱의 무료 사용 설정·실제 메시지 전송과 실기기 QR 스캔을 확인한다.
4. 외부 프록시에서 공개 `/l`과 비공개 `/api/v1` 라우팅을 분리하고, 분산 요청률 제한과
   `/l/{code}` 접근 로그 마스킹을 적용한다. 분산 제한 구현은 준비되었으며 실제 Redis·Ingress 검증은 남아 있다.
5. 실제 비공개 클러스터에서 DNS·TLS·CNI·NetworkPolicy·startup/liveness/readiness probe와
   PVC·Secret의 생성·교체·복구·삭제를 검증한다. 현재 ingress 전용 NetworkPolicy에 더해 환경별 DNS·MySQL
   egress 허용 목록과 기본 차단 정책을 정하고 실제 CNI의 허용·차단 결과를 기록한다.
   `restricted` 정책 강제 적용 전에는 고정 MySQL 이미지로 신규·복원·현재 PVC의 기동,
   TLS·초기화·마이그레이션을 검증한다.
6. 릴리스 이미지 다이제스트, SBOM, 취약점 검사, 서명·빌드 출처 검증 결과를 보존하고
   승인된 이미지만 배포되는지 확인한다.
7. Prometheus 수집, 경보 규칙, 알림 경로와 담당자를 연결하고 마이그레이션 실패,
   Pod 비정상, 5xx·429, DB 준비 상태, 저장 대상 계약 위반, PVC 용량과 백업 실패 경보의
   시험 결과를 기록한다.
8. 재시도 시 기존 URL 반환 보장 기간과 만료·폐기 링크 보존 기간, 자동 정리 뒤 HTTP 의미, 백업·감사
   보존과 PVC 경보·증설 기준을 함께 결정한다. 정리 구현은 준비되어 있으며 이 결정 전에는
   자동 정리를 활성화하지 않는다.
9. 플랫폼 백업 정책에 RPO·RTO·주기·보존 기간·담당자·실패 경보·결과 보관 위치를 명시하고,
   DB와 같은 버전의 `BATON_GO_LINK_CODE_SECRET`을 한 복구 단위로 사용한 격리 복원,
   장애 대응과 이미지 되돌리기 훈련을 완료한다.
10. HMAC 비밀값 유출 시 Secret만 바꾸지 말고 링크 생성과 공개 경로를 먼저 차단한다.
    기존 링크 폐기·재발급과 키 목록 전환을 함께 수행하는 복구 절차를 정하고 훈련한다.
    평상시 키 교체도 [운영 절차](docs/RUNBOOK/link-code-key-rotation.md)에 따라 배포·전환·복원을 검증한다.

운영·복구 절차는
[비공개 Kubernetes 배포 절차](docs/RUNBOOK/kubernetes-private-server-deployment.md)와
[HMAC 키 정보 등록 결정](docs/ADR/0004_link-code-key-binding/adr.md)을 따른다.
