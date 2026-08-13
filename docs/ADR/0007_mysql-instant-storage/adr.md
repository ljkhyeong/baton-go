# ADR-0007: MySQL 절대 시각 저장 형식

- 상태: 채택
- 결정일: 2026-08-08

## 배경

BATON GO는 링크의 생성, 활성 시작, 만료와 폐기 시각을 UTC `Instant`로 다룬다. 최초
스키마는 이 값을 MySQL `TIMESTAMP(6)`로 저장했지만, `TIMESTAMP`의 상한은 2038년이므로
Java `Instant`와 API가 표현할 수 있는 2040년 이후의 정상 시각을 영속화하지 못한다.

MySQL `DATETIME`은 시간대를 자체 저장하거나 변환하지 않으므로, 절대 시각으로 사용하려면
애플리케이션과 JDBC 경계가 UTC 의미를 일관되게 부여해야 한다.

## 결정

- `smart_links.not_before`, `smart_links.expires_at`, `smart_links.revoked_at`,
  `smart_links.created_at`과 `link_creation_requests.created_at`은 `DATETIME(6)`로 저장한다.
- 도메인과 API의 절대 시각 타입은 계속 UTC `Instant`를 사용하고 DB 정밀도에 맞춰
  마이크로초까지만 저장한다.
- Hibernate의 `hibernate.jdbc.time_zone=UTC` 설정과 명시적 JDBC 읽기·쓰기의 UTC
  `Calendar` 정책을 유지한다. `DATETIME(6)` 값을 서버 또는 호스트의 로컬 시각으로
  해석하지 않는다.
- V4 Flyway 마이그레이션은 세션 시간대를 UTC로 고정한 상태에서 기존 `TIMESTAMP(6)` 값을
  `DATETIME(6)`로 변환한 뒤 이전 세션 시간대를 복원한다.
- 기존 null 허용 여부, 만료 시각 검사 제약 조건, 고유 제약 조건과 만료 인덱스는
  변경하지 않는다.
- MySQL 자체는 `1000-01-01`부터 저장할 수 있지만 Java/JDBC 기본 `GregorianCalendar`는
  1582년 이전 날짜에 율리우스력 전환을 적용해 원시 날짜를 이동시킬 수 있다. API 지원
  최소값은 원시 SQL과 같은 역산 그레고리력 날짜가 보존되는
  `1582-10-15T00:00:00Z`로 제한한다.

## 결과

- 지원 범위 안에서 2038년 이후의 링크 수명주기 시각을 저장하고 재조회할 수 있다.
- DB 값에는 오프셋 정보가 없으므로 모든 새 영속화 경로와 운영 SQL은 UTC 의미를
  명시해야 한다.
- 시간대가 있는 달력 의미가 필요해지면 이 저장 규칙을 재사용하지 않고 별도 `ZoneId`
  정책과 계약을 정의한다.
