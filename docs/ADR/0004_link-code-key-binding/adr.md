# ADR-0004: 링크 코드 HMAC 키와 데이터베이스 결합

- 상태: 채택
- 결정일: 2026-08-02

## 배경

공개 코드는 멱등성 키와 `BATON_GO_LINK_CODE_SECRET`의 HMAC으로 결정된다. 다른 secret으로
기존 생성 요청을 재생하면 다른 공개 코드가 만들어지므로 ADR-0003은 저장된 code hash와
현재 파생 결과를 비교해 잘못된 URL 반환을 막았다.

이 값은 Spring `${...}` placeholder 문법과 우연히 같은 문자열을 포함할 수 있다. 서버만
placeholder를 재해석하고 guard-tool은 process environment 원문을 사용하면 같은 배포 입력도
서로 다른 HMAC key identity가 되어 시작·복구 계약을 만족할 수 없다.

하지만 재생 요청이 오기 전에는 설정 오류를 발견하지 못한다. 잘못된 secret으로 시작한
replica가 새 생성 intent를 먼저 처리하면 한 데이터베이스에 서로 다른 키로 파생된 링크가
섞이고, 어느 secret으로도 전체 데이터를 재생할 수 없게 된다.

## 결정

- Flyway가 `link_code_key_guard` singleton 행 `guard_id=1`을 만든다.
- singleton은 링크 코드 파생 규약 version과 HMAC key fingerprint를 함께 저장한다.
- identity는 다음 값으로 고정한다.
  - version: `hmac-sha256-link-code-v1`
  - fingerprint:
    `hex(HMAC-SHA-256(secret, "baton-go-link-code-key-fingerprint:v1\0"))`
- fingerprint는 secret 자체가 아니지만 로그, 오류 응답과 운영 티켓에 기록하지 않는다.
- 서버와 guard-tool은 `BATON_GO_LINK_CODE_SECRET`을 placeholder 해석 없는 raw process
  environment 값으로 읽고 trim·Unicode 정규화·문자열 치환 없이 같은 UTF-8 key bytes로 쓴다.
- 애플리케이션 시작의 `ApplicationRunner`가 현재 identity와 DB identity를 검증한다.
- 링크 생성 transaction은 예약 행을 만들기 전에 같은 검증을 다시 수행한다.
- DB identity가 이미 결합되어 있으면 version과 fingerprint가 모두 일치해야 한다.
- 미결합 상태에서는 `smart_links`와 `link_creation_requests`가 모두 비어 있을 때만 현재
  identity에 자동 결합한다.
- 불일치, 부분 결합, singleton 유실 또는 기존 데이터가 있는 미결합 상태는 하나의 안전한
  오류로 실패하며 secret과 fingerprint를 메시지에 포함하지 않는다.
- ADR-0003의 재생 code hash 검증은 손상과 구현 실수를 막는 방어 계층으로 유지한다.

## 동시성과 transaction 경계

시작 검증은 singleton 행을 `SELECT ... FOR UPDATE`로 잠그고 identity와 업무 테이블의 빈
상태를 확인해 필요하면 결합한다. 같은 identity를 가진 replica는 최초 결합 뒤 모두 통과한다.
서로 다른 identity의 replica가 빈 DB에 동시에 시작하면 먼저 잠근 하나만 결합하고 나머지는
불일치로 실패한다.

생성 경로는 자동 결합하지 않고 `SELECT ... FOR SHARE` current read로 이미 결합된 identity만
검증한다. 공유 잠금은 정상 생성끼리 호환되므로 singleton 때문에 전역 직렬화되지 않고,
MySQL repeatable-read의 과거 snapshot을 만들지 않아 멱등 예약 승자의 commit 관찰도 방해하지
않는다. 시작 runner보다 요청이 먼저 도착해 singleton이 미결합이면 저장 전에 실패한다.

시작 검증과 웹 서버 초기화 사이에 생성 요청이 들어올 수 있으므로 시작 검증만 신뢰하지
않는다. `SmartLinkService.createLink`가 같은 guard를 생성 예약보다 먼저 확인해, 검증 실패
시 링크와 예약이 한 건도 저장되지 않게 한다.

## 배포와 복구

신규 DB는 Flyway가 미결합 singleton을 만든 뒤 시작 검증이 자동 결합한다.

기존 링크나 생성 예약이 있는 DB는 현재 secret을 증명할 원문 멱등성 키가 없으므로 자동
결합하지 않는다. 최초 도입은 다음 순서를 따른다.

1. 모든 구버전 writer와 생성 트래픽을 중지한다.
2. secret manager에서 기존 배포에 사용한 불변 secret version을 복구한다.
3. 안전하게 보관한 canary 생성 intent가 있다면 현재 파생 code hash와 DB의 예약-링크
   code hash가 같은지 오프라인으로 검증한다.
4. 검증된 배포 도구가 singleton의 version과 fingerprint를 한 transaction에서 결합한다.
5. 새 버전을 시작하고 readiness가 열리기 전에 guard 검증이 통과하는지 확인한다.

검증된 배포 도구는 release source에서 별도 생성하는
`guard-tool` 모듈의 `baton-go-guard-binding.jar`다. 이 모듈은 application, 링크 코드
external adapter와 MySQL JDBC를 조합하며 일반 서버의 웹 서버·Hibernate/Spring Data JPA·
Actuator runtime을 포함하지 않는다. 일반 애플리케이션의 우회 flag나 관리 HTTP endpoint가
아니며, writer 중지 확인
인자와 stdin canary를 요구한다. 도구는 canary 멱등성 키의 예약 해시와 현재 비밀에서 파생한
code hash가 같은 저장 링크에 결합됐는지 확인한 뒤 guard row를 잠그고 같은 transaction에서
최초 결합한다. 세부 절차는
[기존 데이터베이스 HMAC guard 최초 결합 runbook](../../RUNBOOK/link-code-key-guard-binding.md)을
따른다.

결합 도구의 canary 입력만 guard 도입 전 생성 규칙과 호환되어야 한다. 과거 규칙과 똑같이
`UUID.fromString`으로 해석한 뒤 `parsed.toString().equalsIgnoreCase(input)`가 참인 문자열을
lowercase canonical UUID로 정규화한다. 따라서 과거에 허용된 uppercase, version 7, nil,
non-RFC variant canary도 검증할 수 있다. 이는 복구 전용 호환 규칙이며 공개 생성 API의
lowercase version `1..5` RFC variant 계약을 완화하지 않는다.

한 번의 결합은 입력한 canary가 현재 secret과 일치한다는 사실만 증명한다. 과거 데이터
전체가 하나의 키로 만들어졌다는 증거나 이미 키가 섞인 DB의 교정 수단은 아니다. mixed-key
가능성이 있으면 결합을 중단하고 별도 inventory와 복구 결정을 수행한다. commit 응답이
유실되어 결과가 불명확하면 writer를 계속 중지한 채 같은 secret과 같은 canary로 도구를
재실행한다. 같은 identity에 이미 commit됐다면 canary를 다시 검증하고 멱등 성공한다.

DB general log, audit plugin과 query tracing은 JDBC prepared statement의 fingerprint와 hash
bind 값을 기록할 수 있다. 결합 전에 해당 로그의 parameter capture를 비활성화하거나
마스킹하고, 이미 수집된 DB 로그는 민감 운영 자료로 제한한다.

canary나 검증된 secret version이 없다면 임의 secret에 DB를 결합하지 않는다. 링크 데이터가
필요 없다면 새 DB로 시작하고, 필요하다면 올바른 secret을 복구할 때까지 배포를 중단한다.
우회 환경 변수나 자동 강제 결합 옵션은 제공하지 않는다.

DB backup과 해당 시점의 HMAC secret version은 하나의 복구 단위다. 복구 훈련은 둘을 함께
복원하고 시작 검증과 canary 재생을 확인해야 한다. 구버전과 신버전의 혼합 배포 중 secret을
변경하지 않는다.

## 결과

- 잘못된 secret이나 파생 규약으로 신규 링크가 섞이기 전에 프로세스가 fail-closed 한다.
- secret 회전은 허용하지 않는다. 회전이 필요하면 생성 요청별 key version, 복수 key 설정과
  단계적 배포를 포함한 versioned key ring을 별도 ADR로 설계한다.
- 공개 링크 해석은 원문 코드의 SHA-256 조회이므로 HMAC guard를 사용하지 않지만, 키
  불일치 프로세스는 시작 단계에서 종료된다.
