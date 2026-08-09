# ADR-0009: 멱등 생성의 공개 origin 보존

- 상태: 채택
- 결정일: 2026-08-09

## 배경

링크 생성 재시도는 동일한 `id`, `shortUrl`과 `Location`을 반환해야 한다. 공개 코드는
HMAC 파생과 DB hash 검증으로 복구할 수 있지만, 기존 구현은 응답 시점의
`BATON_GO_PUBLIC_BASE_URL`로 `shortUrl`을 다시 조립했다. 재시작·복구·rolling deployment에서
host나 port 설정이 바뀌면 같은 생성 intent가 다른 URL을 반환할 수 있었다.

공개 origin을 데이터베이스 전체 singleton으로 고정하면 오류는 막을 수 있지만 정상적인
origin 이전도 전체 데이터 수명주기에 결합된다. 링크마다 전체 short URL이나 원문 공개
코드를 저장하는 방식은 기존 최소 저장 원칙과 맞지 않는다.

## 결정

- `link_creation_requests`에 생성 승자가 사용한 canonical `public_origin`을 저장한다.
- origin은 scheme과 host를 lowercase로 정규화하고 HTTP 80·HTTPS 443 기본 port와 root
  slash를 제거한다. 동등한 IPv6 literal도 같은 JDK 주소 표현으로 정규화한다.
- 비로컬 origin은 HTTPS만 허용하고 저장값은 ASCII 255자 이하여야 한다.
- 예약 경쟁의 승자가 origin을 함께 저장한다. 패자와 이후 재시도는 현재 설정이 아니라
  예약에 저장된 origin을 읽어 같은 공개 코드와 결합한다.
- 저장 origin이 없거나 비canonical·비허용 값이면 현재 설정으로 보정하거나 추정하지 않고
  `PUBLIC_LINK_ORIGIN_REPLAY_UNAVAILABLE`로 fail-closed 한다.
- DB에는 계속 공개 코드 원문과 전체 short URL을 저장하지 않는다.
- 공개 origin은 호출자 payload가 아니므로 같은 멱등성 키의 payload 충돌 비교 항목에는
  넣지 않는다. 서로 다른 설정의 replica가 경쟁해도 먼저 저장한 origin이 응답 identity다.

## migration과 운영

Flyway V5는 기존 행과의 schema 호환을 위해 `public_origin`을 nullable로 추가한다. 새
애플리케이션이 만든 예약은 항상 값을 저장한다. V5 이전 예약은 최초 URL을 증명할 별도
자료가 없으면 자동 backfill하지 않으며 재생 시 안전한 운영 오류로 실패한다. 검증된 최초
URL inventory가 있는 경우에만 maintenance 절차로 canonical origin을 backfill한다.

V5는 column 추가 관점에서는 expand migration이지만 구 application writer와의 동작 호환
migration은 아니다. V5 적용 뒤에도 구 writer는 column을 생략한 예약을 성공시킬 수 있고 그
intent는 새 application에서 정확한 URL을 재생할 수 없다. 이미 실행 중인 배포에 V5를 도입할
때는 관리 생성 writer를 차단·drain하고 구 Pod를 0으로 만든 뒤 migration과 새 application을
적용한다. 일반 rolling update로 취급하지 않는다.

Private Kubernetes 첫 배포는 신규 GO 전용 DB를 사용하므로 모든 생성 예약이 V5 이후
형식으로 시작한다.

## 결과

- 공개 origin 변경과 replica 설정 차이에도 기존 생성 intent의 exact URL 재생이 유지된다.
- 정상적인 새 origin 이전은 신규 intent부터 적용할 수 있어 DB 전체 identity를 영구 고정하지
  않는다.
- 이전 schema의 예약은 가용성보다 잘못된 URL 반환 방지를 우선한다.
