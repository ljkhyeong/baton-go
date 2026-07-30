# ADR-0001: 독립 링크 게이트웨이 서비스

- 상태: 채택
- 결정일: 2026-07-29

## 배경

BATON과 ROUND는 서로 다른 배포·실행 구조를 가지지만 조직 링크와 회의 링크의 코드,
만료, 폐기와 공개 진입 규칙을 공유한다. BATON 내부 모듈로만 구현하면 ROUND와 향후 다른
제품이 같은 규칙을 다시 구현하거나 BATON 배포에 종속된다.

반대로 링크 서비스가 workspace 멤버십이나 room 입장 권한까지 소유하면 원 도메인의
상태를 복제하고 매 요청마다 동기 호출해야 한다.

## 결정

BATON GO를 독립 데이터베이스와 배포 수명주기를 가진 마이크로서비스로 만든다. 내부는
다음 포트·어댑터 모듈을 사용한다.

```text
bootstrap
 ├─ adapter-in-web
 ├─ adapter-out-persistence
 ├─ adapter-out-external
 ├─ application
 └─ domain
```

GO가 소유하는 상태:

- 공개 코드 해시
- 대상 시스템과 안전한 대상 경로
- 링크 목적
- 활성 시작, 만료와 폐기 시각
- 향후 resolution/redemption 감사 이벤트

GO가 소유하지 않는 상태:

- BATON access key와 workspace 권한
- BATON 사용자·멤버·역할 권한 snapshot
- ROUND peer identity, room membership와 TURN credential

## 통합 규칙

- BATON의 저장 transaction 안에서 원격 링크를 만들지 않는다.
- BATON projection 조회에서 링크마다 GO를 호출하지 않는다.
- 권한이 필요한 링크는 대상 서비스가 먼저 권한을 검사하고 짧은 수명의 locator 또는
  향후 redemption intent만 GO에 요청한다.
- GO는 최종 권한을 나타내는 JWT나 session을 독자적으로 발급하지 않는다.

## 결과

장점:

- 공개 리졸버의 배포와 rate limit, 관측성을 독립적으로 운영할 수 있다.
- BATON과 ROUND가 같은 링크 수명주기 규칙을 재사용한다.
- GO 장애를 링크 열기 흐름에 한정할 수 있다.

비용:

- 별도 MySQL, 백업·복구, 비밀과 모니터링이 필요하다.
- 원격 생성 idempotency 예약 상태와 별도 링크 코드 파생 비밀을 운영해야 한다.
- BATON Caddy와 CSP, production Compose와 smoke/restore 절차를 확장해야 한다.

초기 구현은 독립 서비스를 세우되 BATON·ROUND 저장소에는 아직 동기 의존성을 추가하지 않는다.
