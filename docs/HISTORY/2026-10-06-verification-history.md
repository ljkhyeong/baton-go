# 2026-10-06 검증 기록

GO `971f5b3` 이후 HANDOFF `최근 검증`에서 현재 판단에 필요 없는 이전 항목을 옮겼다.
아래 상태와 미실행 항목은 각 기록의 확인 시점 기준이다. 현재 상태와 다음 작업은
[HANDOFF](../../HANDOFF.md)를 확인한다. 더 이전 기록은
[2026-10-05 검증 기록](2026-10-05-verification-history.md)에 있다.

## 검증 기록

- 3차 정리는 미커밋 변경이 없는 `main`의 `d9aa3a7`에서 시작해 `f334005`·`a5d39a9`·`90e7935`·`fc2dfde`·`e6e511d`와
  문서 커밋에 저장했다. 공개 링크 오류 처리기 쌍 12개를 협상 매핑 2개와 예외 패턴 분기로 합치고, 도달하지 않는
  대상 정책 null 검증과 서비스 매개변수 풀기를 정리했다. 단일 비밀값 키 ID를 `legacy`에서 `default`로 바꾸고 키 확인
  통합 테스트를 다른 키 테스트 패키지로 옮겼다. 운영 코드 155줄을 지우고 45줄을 더했다. Gradle 권장 방식대로 기존
  파일을 치운 오프라인 `--write-verification-metadata sha256 --dry-run` 결과와 대조해 JPA 계열 검증 항목 34개를
  지웠다. 처음 48개를 지운 시도는 캐시 없는 이미지 빌드가 플러그인 경로의 `tools.jackson:jackson-base:3.1.5` POM을
  요구해 실패했고, 공통 BOM·부모 POM을 남기는 방식으로 바꿨다. `--refresh-dependencies build`와 `docker build --no-cache`
  가 검증을 통과했다. Java 21에서 결과 디렉터리를 비운 뒤 `./gradlew --no-daemon build :adapter-in-web:apiContractDocs
  :bootstrap:mysqlTest :bootstrap:redisTest`를 통과했다. 도메인 73·애플리케이션 42·웹 124·외부 25·bootstrap 31·
  MySQL 29·Redis 3개에 실패·제외가 없고 REST Docs 조각은 84개다. 최종 트리의 `docker build`도 성공했다.
  로그는 같은 스크래치패드의 `r3-*.log`·`verif-*.log`다. 생성·조회 사용 사례 분리, 관리 목록 쿼리 객체 바인딩,
  JWK 테스트 서버 공용화와 Spring Data Redis 전환은 이점보다 변경 범위가 커 하지 않았다. 이후 원격 `main`에 반영했고
  `41348ab`의 [원격 CI](https://github.com/ljkhyeong/baton-go/actions/runs/37260550380)가 성공했다.
- 2차 과감한 정리는 미커밋 변경이 없는 `main`의 `48bb23e`에서 시작해 `9dab25f`·`772a3bb`·`9eeb4da`·`f4eda73`·
  `fea3c98`에 저장했다. 운영 데이터와 적용된 DB가 없다는 사용자 확인에 따라 JPA를 제거해 영속성을
  `JdbcClient`로 통일하고, V1~V7을 최종 스키마의 V1 하나로 다시 만들었다. 보호 행·`version` 열·쓰이지 않던
  만료 인덱스를 없애고 정리 전 예약의 공개 출처를 검사 제약으로 강제했다. 저장 링크 레코드 3종을 `StoredLink`로
  합치고 `FilterErrorResponseWriter`를 지워 보안 필터 오류를 MVC 예외 처리기로 모았다. 운영 코드 603줄을 지우고
  196줄을 더했으며 테스트는 504줄을 지우고 103줄을 더했다.
  Java 21에서 결과 디렉터리를 비운 뒤 `./gradlew --no-daemon build :adapter-in-web:apiContractDocs
  :bootstrap:mysqlTest :bootstrap:redisTest`를 통과했다. 도메인 73·애플리케이션 42·웹 124·외부 25·bootstrap 31·
  MySQL 29·Redis 3개에 실패·제외가 없고 REST Docs 조각은 84개다. `docker build`와 CI의 운영 이미지 기동 단계를
  같은 스크립트로 로컬 실행해 통과했다. 실행 클래스패스에 Hibernate ORM·Spring Data JPA가 없음을 확인했다.
  MockMvc는 HEAD 본문을 버리지 않아 관리 HEAD 403의 빈 본문 확인을 실제 HTTP 서버 테스트로 옮겼다.
  로그는 같은 스크래치패드의 `bold-*.log`와 `smoke/run.log`다. Gradle 검증 메타데이터의 쓰이지 않는 JPA 항목은
  다시 생성하려면 네트워크 조회가 필요해 남겼고 3차 정리에서 지웠다. 이후 원격 `main`에 반영했고
  `41348ab`의 [원격 CI](https://github.com/ljkhyeong/baton-go/actions/runs/37260550380)가 성공했다.
- 과거 호환 정리는 미커밋 변경이 없는 `main`의 `bfb9a14`에서 시작해 `7c1c0a0`·`274fb64`·`110abfd`·`c950b5d`·
  `c3e2e0b`·`c3040ae`에 저장했다. 보존할 기존 GO DB가 없다는 사용자 확인에 따라 과거 형식 멱등 재시도,
  `guard-tool` 모듈·CI 단계, 대상 계약 운영 API와 점검 전용 예약 존재 조회를 제거했다. 예시에서 사라진 공개
  비밀값 차단, 내부 키 지문 형식 검사, 설정과 겹친 발급 키 검사도 지웠다. 폐기 시각 검사의 500은
  `274fb64`에서 생성 시각 보정으로 고쳤다. 운영 코드 818줄, 테스트 1,431줄, 문서·스킬 442줄을 줄였다.
  Java 21에서 `./gradlew --no-daemon build :adapter-in-web:apiContractDocs :bootstrap:mysqlTest
  :bootstrap:redisTest`를 통과했다. 도메인 73·애플리케이션 42·웹 123·외부 25·bootstrap 31·MySQL 32·Redis 3개에
  실패·제외가 없고 REST Docs 조각은 84개다. `docker build`도 성공했다. 단계마다 대상 테스트를 먼저 실행했으며,
  3단계에서 guard-tool의 과거 대문자 검증용 요청 테스트가 실패해 같은 단계에서 제거했다. CI 변경은
  actionlint 1.7.12, 설정 변경은 Compose·Kustomize 렌더, 문서는 전체 링크 검사로 확인했다. 로그는 같은
  스크래치패드의 `cleanup-*.log`다. 이후 원격 `main`에 반영했고
  `41348ab`의 [원격 CI](https://github.com/ljkhyeong/baton-go/actions/runs/37260550380)가 성공했다.
- 정리·커서 리팩터링은 미커밋 변경이 없는 `main`의 `6c09109`에서 시작해 `6af1a72`·`07ffbb8`에 저장했다.
  영속성 어댑터가 계산하던 정리 요청 해시를 `LinkRetentionService`로 옮겨 어댑터는 잠금 조회와 정리 SQL만 맡고,
  `LinkCreationFingerprint`는 애플리케이션 패키지 안으로 숨겼다. 관리 목록과 대상 계약 점검에 중복된
  `limit + 1`건 조회·최대 500건·다음 커서 규칙은 `StoredLinkScan`으로 모았다. SQL·잠금·오류 코드·응답은 같다.
  Java 21에서 `./gradlew --no-daemon test`와 `:bootstrap:mysqlTest --tests '*LinkRetentionIntegrationTest'
  --tests '*TargetContractOperationsIntegrationTest' --tests '*LinkPersistenceIntegrationTest'`를 통과했다.
  도메인 72·애플리케이션 48·웹 135·외부 26·guard-tool 4·bootstrap 32·MySQL 21개 테스트에 실패·제외가 없고
  계층 규칙 5개도 통과했다. 스케줄러 테스트는 포트 대신 서비스를 목으로 쓰고, 정리 통합 테스트는 실제 서비스를
  거쳐 정리한다. 로그는 같은 스크래치패드의 `refactor-*.log`다. 키 가드 어댑터의 키 묶음 판정은 잠금 순서와
  얽혀 있어 이번 범위에서 제외했다. Redis·배포 구성은 바뀌지 않아 Redis 통합 검증과 이미지 빌드는 하지 않았다.
- 이미지 증명 추가는 미커밋 변경이 없는 `main`의 `0983bf2`에서 시작해 워크플로를 `64e4d75`에 저장했다.
  다음 작업 6의 서명·빌드 출처 검증을 위해 `actions/attest` v4.2.2(`1e69f48`)로 증명을 만드는
  `image-attestation` 작업과 보관 워크플로의 재검증을 추가했다. `id-token`·`attestations: write`는 새 작업에만 준다.
  actionlint 1.7.12(ShellCheck 0.11.0)로 워크플로 3개를 통과했다. 추출한 실제 단계 스크립트를 Ubuntu 24.04·
  bash 5.2·jq 1.7과 대체 `gh`·`docker`·`git`으로 실행해 증명 대상 9건(정상·대문자 저장소·커밋/실행/검사
  불일치·다른 저장소·다이제스트 형식), CI 검증 3건, 보관 4건의 16개 시나리오를 통과했다. 첫 실행의 4건 실패는
  대체 도구의 기록 형식과 시나리오 실행 주소의 대소문자 오류였다. `gh` 2.86.0의 검증 플래그는 공개
  `ghcr.io/actions/actions-runner` 증명에서 일치 시 통과, 소스 커밋·ref·서명 워크플로 불일치 시 거부를 확인했다.
  CI와 같은 Trivy 0.72.0 `convert`의 CycloneDX가 `actions/attest`의 판별 필드를 갖는 것도 네트워크 없이 확인했다.
  변경 문서 4개의 링크·앵커 문제는 0건이고 배포 전 확인 명령은 Bash 구문·ShellCheck를 통과했다. 도구와 로그는
  스크래치패드 `9beb8bea-0936-4cc1-98ce-24ef4a8084ad/scratchpad/attestation-validation`의 `run-scenarios.sh`·
  `scenarios.log`·`actionlint-attestation.log`다. Java·DB·이미지가 바뀌지 않아 Gradle·MySQL·Redis 검증과
  이미지 빌드는 하지 않았다. 이후 원격 `main`에 반영했고 `a20146e`의
  [원격 CI](https://github.com/ljkhyeong/baton-go/actions/runs/37253336070)는 필수 검증·의존성 제출·출처 증명
  세 작업이 모두 성공했다. 증명 작업의 생성·배포 기준 검증 단계도 성공했고, 증명 API에서 게시 다이제스트
  `sha256:4e240e27…`의 SLSA·CycloneDX 증명 2개를 조회했다. 기록 커밋 `6c09109`의 원격 CI도 세 작업이 모두
  성공했다. 로컬 GHCR 인증이 필요한 배포 전 확인 명령과 Release 생성은 실행하지 않았다.
