# ADR-0003: 멱등한 링크 생성

- 상태: 채택
- 결정일: 2026-07-29

## 배경

BATON과 ROUND가 링크를 원격 생성할 때 서버는 저장을 완료했지만 응답이 유실될 수 있다.
기존 무작위 발급을 그대로 재시도하면 여러 링크가 생기고, DB에 원문 코드를 저장하지
않으므로 최초 short URL을 복구할 수도 없다.

## 결정

- `POST /api/v1/links`는 생성 intent마다 canonical UUID `Idempotency-Key`를 요구한다.
- 관리 credential과 분리된 `BATON_GO_LINK_CODE_SECRET`을 사용한다.
- 공개 코드는
  `first16(HMAC-SHA-256(secret, "baton-go-link-code:v1\0" + idempotencyKey))`로 파생한다.
- DB에는 공개 코드와 멱등성 키의 SHA-256 해시만 저장한다.
- `link_creation_requests`가 멱등성 키 해시와 링크 ID를 연결한다.
- MySQL `INSERT IGNORE`의 unique-key 대기를 승자 선택 경계로 사용한다. 중복 insert가
  반환된 뒤에는 승자 transaction이 커밋되었으므로 일반 조회로 완성된 링크를 읽는다.
- 같은 키와 payload는 동일 URL을 재생하고 같은 키의 다른 payload는 `409`로 거부한다.
- 재생 응답은 최초 snapshot이 아니라 링크의 현재 폐기 상태를 반환한다.

## 트랜잭션 경계

예약 생성, 링크 생성과 저장은 하나의 transaction에서 수행한다. 승자 transaction이
rollback되면 예약 행도 함께 사라져 대기 중인 요청 하나가 새 승자가 된다.

`INSERT IGNORE` 뒤에 패자들이 `SELECT ... FOR UPDATE`로 잠금을 승격하면 교착이 발생할 수
있으므로 후속 조회에는 비관 잠금을 사용하지 않는다. 이 동작은 MySQL Testcontainers의
동시 요청 테스트로 고정한다.

## 운영 결과

- 서비스 재시작, replica 변경과 DB 복구 뒤에도 같은 비밀을 사용해야 같은 URL을 재생한다.
- key ring과 key version을 도입하기 전에는 링크 코드 파생 비밀을 회전하지 않는다.
- BATON과 ROUND는 원본 aggregate commit 뒤 GO를 호출하고 생성 intent UUID를 outbox 또는
  소유 상태에 보존한다.
- 관리 credential과 파생 비밀을 공유하지 않고 둘 다 로그와 URL에 넣지 않는다.
