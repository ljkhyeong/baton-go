---
name: baton-go-verify
description: BATON GO에서 코드·설정·문서를 바꾼 뒤 필요한 최소 검증을 고르고 실행·기록할 때 사용한다. 변경 파일을 Gradle 작업·MySQL·Redis 통합 검증·Kustomize·Prometheus·웹훅·문서 링크 검사에 대응시킨다.
argument-hint: "[시작 리비전]"
---

# BATON GO 검증

기준은 `docs/RUNBOOK/development-verification.md`다. 변경한 동작부터 확인하고, 영향이 다른 계층까지
이어질 때만 범위를 넓힌다.

## 1. 검증 범위 선택

시작 리비전(인자 또는 작업 시작 때 기록한 값)을 기준으로 변경 파일별 검증을 추천받는다.

```bash
python3 .claude/skills/baton-go-verify/scripts/select_checks.py --base <시작 리비전>
```

- 미커밋·미추적 파일을 포함한다. `--base`를 생략하면 `HEAD` 대비 미커밋 변경만 본다.
- 추천은 경로 기준이므로 출발점으로만 쓴다. 다음은 경로로 알 수 없으므로 직접 판단한다.
  - `application.yml`의 Redis·DB 설정 변경 → `:bootstrap:redisTest`·`:bootstrap:mysqlTest`
  - Java `package` 선언 변경 → `:bootstrap:test --tests '*ArchitectureRulesTest'`
  - 포트 시그니처 변경처럼 하위 계층이 따라 바뀌는 경우 → 영향받는 모듈 테스트
- 테스트 클래스를 알면 `--tests`로 좁힌다. 여러 Gradle 작업은 한 명령으로 묶고 선행 컴파일은 Gradle에 맡긴다.
- 문서·주석·`@DisplayName`만 바꿨으면 빌드·테스트를 생략하고 `git diff --check`와 링크 검사만 한다.

## 2. 파일 단위 즉시 확인

파일을 작성·수정한 직후 다음을 실행한다. 새 파일은 `git diff`에 나오지 않으므로 내용을 직접 읽는다.

```bash
git diff --check -- <파일>
python3 .claude/skills/baton-go-verify/scripts/check_doc_links.py [md 파일...]   # 인자 없으면 전체 md
```

링크 검사는 상대 경로 대상과 GitHub 방식 제목 앵커(`## 코드·연동 준비` → `#코드연동-준비`)를 확인한다.
문서 제목·경로를 바꿨다면 인자 없이 전체를 검사한다.

## 3. 실행과 기록

- 긴 출력은 로그로 남기고 결과·실패 부분만 읽는다. 기존 기록은
  `baton-go-<작업>-<YYYYMMDD>.log` 이름을 쓴다. 세션 전용 임시 디렉터리가 있으면 그 아래에 둔다.

  ```bash
  ./gradlew --no-daemon <작업...> > <로그 경로> 2>&1; echo "exit=$?"
  ```

- 결과는 테스트 수와 실패·제외 수까지 확인한다. 예: `build/test-results/**/TEST-*.xml`의 `tests`·`failures`·`skipped`.
- `mysqlTest`·`redisTest`는 Docker가 필요하다. 실행하지 못했으면 미실행으로 기록하고 일반 `test` 성공으로 대신하지 않는다.

## 4. 실패 분류

- 코드·테스트 실패: 실패한 테스트부터 고치고 같은 범위를 재실행한다.
- 환경 실패(Gradle 캐시 잠금, Docker 미실행, 포트·샌드박스 권한, 의존성 다운로드): 같은 조건으로 반복하지 않는다.
  권한은 정식 승인 절차로 요청하고, 잠금·캐시를 임의로 삭제하지 않는다.
- 실행되지 않은 테스트(필터 불일치 등)를 통과로 기록하지 않는다.

## 5. 결과 재사용

검증 대상 코드·테스트·의존성·설정·실행 환경이 같으면 기존 성공을 재사용한다. 문서 수정이나 커밋 생성만으로
테스트를 반복하지 않는다. 변경 여부가 불명확하면 필요한 범위만 재실행한다.
Gradle이 추적하지 못하는 환경이 바뀌었을 때만 `--rerun-tasks`를 사용한다.
