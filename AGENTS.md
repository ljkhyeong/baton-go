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

- 새 문서와 기존 문서의 변경은 한국어로 작성한다. API 경로, HTTP 헤더, 오류 코드,
  클래스·메서드·설정 키, 명령, 정규식과 외부 표준 용어처럼 정확성이 필요한 리터럴은
  번역하지 않는다.
- 새 검증기, 파서, 래퍼나 추상화를 만들기 전에 JDK, Java, Spring과 이미 사용하는
  라이브러리의 표준 API 및 저장소의 기존 추상화를 먼저 찾는다. 계약 의미가 다를 때만
  직접 구현하고 그 차이를 테스트나 장기 문서에 남긴다.
- 같은 계약은 가장 좁은 소유 계층에서 한 번 검증한다. 다른 계층에서는 신뢰 경계,
  보안 심층 방어, 실제 Spring 조립, DB 동시성처럼 별도 실패 가능성이 있을 때만 다시
  검증한다. 표준 라이브러리 자체 동작을 그대로 재시험하거나 테스트 전용 운영 API를
  유지하지 않는다.
- 테스트는 계약별 소유 테스트와 필요한 통합 경계만 둔다. 같은 입력 행렬과 같은 결과를
  단위·HTTP·통합 테스트에 반복하지 않고, 구현 세부 메시지·내부 예외·라이브러리 오류
  번호보다 외부 동작과 상태 불변식을 검증한다.
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
