# 저장소 작업 지침

## 기준 문서

- 작업 전 `HANDOFF.md`와 `README.md`를 읽는다.
- 제품 동작은 `docs/PRD/0001_product-baseline/spec.md`, HTTP 계약은
  `docs/PRD/0002_api-contract/spec.md`를 기준으로 삼는다.
- 장기 구조 결정은 `docs/ADR/`에 기록한다.

## 서비스 경계

- BATON GO는 링크 코드, 활성 시간, 만료, 폐기와 신뢰된 대상 라우팅을 소유한다.
- BATON의 작업 공간·역할·회차 권한과 ROUND의 방 입장 권한을 복제하지 않는다.
- BATON 접근 키, ROUND 참여 허가, Bearer 토큰과 세션 식별자를 링크 대상이나 로그에 넣지 않는다.
- BATON 저장 트랜잭션이나 작업 공간 투영 조회 안에서 원격 링크를 생성하지 않는다.

## 모듈과 의존 방향

- `domain`: 링크 엔티티, 값 객체, 시간·상태 불변식
- `application`: 사용 사례와 `port.in`/`port.out`
- `adapter-in-web`: HTTP DTO, 컨트롤러, 필터, 오류 직렬화
- `adapter-out-persistence`: JPA 저장소와 영속성 포트 구현
- `adapter-out-external`: 신뢰 대상 URL 조립과 향후 BATON·ROUND HTTP 어댑터
- `guard-tool`: 기존 DB HMAC 보호 장치 최초 결합을 위한 일회성 JDBC CLI
- `bootstrap`: Spring Boot, 설정, Flyway와 실행 환경 조립

운영 코드의 의존 방향은 `bootstrap → adapters → application → domain`과
`guard-tool → adapter-out-external/application → domain`이다. 컨트롤러에 업무 규칙을
두지 않고 애플리케이션 계층은 어댑터를 참조하지 않는다.

## 구현 규칙

- Java 21과 주입된 `Clock`을 사용한다. 업무 코드에서 `Instant.now()`를 직접 호출하지 않는다.
- 절대 시각은 `Instant`와 UTC DB 타임스탬프로 저장한다. 달력 의미가 생길 때만 명시적
  `ZoneId` 정책을 추가한다.
- 링크 생성은 정규 UUID `Idempotency-Key`를 요구한다. 공개 코드는 별도 32자 이상
  비밀값을 사용한 HMAC-SHA-256에서 128비트로 파생하고 DB에는 공개 코드와 멱등성 키의
  SHA-256 해시만 저장한다.
- 대상은 승인된 시스템과 `/`로 시작하는 상대 경로로 제한한다. 스킴, 호스트, 프래그먼트,
  쿼리와 `//` 경로를 받지 않는다.
- 공개 `GET`은 사용 횟수를 소비하거나 상태를 바꾸지 않는다. 일회성 교환은 인증 후
  별도 `POST` 계약으로 추가한다.
- 오류는 안정적인 `UPPER_SNAKE_CASE` 코드와 사용자용 메시지, 선택적 `requestId`를 사용한다.
- Flyway 마이그레이션은 적용된 파일을 수정하지 않고 다음 버전을 추가한다.
- 비밀값과 환경별 주소는 환경 변수로 주입한다.
- `Idempotency-Key`, 링크 코드 파생 비밀값과 전체 단축 URL을 로그에 기록하지 않는다.

## 검증

- 도메인 정책: `./gradlew :domain:test`
- 애플리케이션 흐름: `./gradlew :application:test`
- 전체 정적·단위 검증: `./gradlew test`
- Spring/Flyway/MySQL 통합: `./gradlew --no-daemon :bootstrap:mysqlTest`
- 모든 테스트 메서드에는 한국어 문장형 `@DisplayName`을 사용한다.
