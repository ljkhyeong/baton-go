---
name: baton-go-deps
description: BATON GO의 Dependabot PR(GitHub Actions, eclipse-temurin 기반 이미지)을 검토하거나 Gradle 의존성·Spring Boot·MySQL 이미지를 직접 올릴 때 사용한다. 고정 SHA·digest, Gradle 검증 메타데이터와 이미지 일치 규칙을 확인한다.
argument-hint: "[PR 번호 | 올릴 의존성]"
---

# BATON GO 의존성 업데이트

Dependabot은 `.github/dependabot.yml`에 따라 매주 월요일 두 묶음만 제안한다.
GitHub Actions 전체와 `eclipse-temurin`(Java 메이저 제외)이다. Gradle 의존성과 MySQL 이미지는 직접 올린다.

## Dependabot PR 검토

```bash
gh pr list --author app/dependabot
gh pr view <번호> --json title,files,statusCheckRollup,mergeable
gh pr diff <번호>
```

### GitHub Actions 묶음

- 모든 `uses:`는 커밋 SHA와 `# vX.Y.Z` 주석으로 고정한다. SHA가 주석의 태그를 가리키는지 확인한다.

  ```bash
  gh api repos/<owner>/<action>/git/ref/tags/<태그> --jq '.object.type + " " + .object.sha'
  # type이 tag면 한 번 더: gh api repos/<owner>/<action>/git/tags/<sha> --jq .object.sha
  ```

- 메이저 버전이 바뀌면 릴리스 노트에서 러너 Node 버전, 기본 입력값(`persist-credentials`, 캐시 등),
  제거된 입력을 확인하고 워크플로에서 쓰는 입력과 대조한다.
- PR CI의 `필수 검증`은 `ci.yml`만 실행한다. `release-evidence.yml`·`release-vulnerability-review.yml`은 수동 실행이라
  PR에서 검증되지 않으므로, 이 파일의 액션이 바뀌면 그 차이를 보고하고 다음 릴리스 때 확인할 항목으로 남긴다.
- `upload-artifact`·`download-artifact`는 산출물 이름(`baton-go-image-security` 등)을 다른 작업·워크플로가 받는다.
  메이저 변경 시 두 쪽 버전 호환을 함께 본다.

### eclipse-temurin 묶음

- `Dockerfile`의 `build`(`21-jdk-alpine`)와 `runtime`(`21-jre-alpine`) 단계가 같은 Java 패치 릴리스로 함께 바뀌는지 확인한다.
- digest는 `docker buildx imagetools inspect eclipse-temurin:<태그>`로 대조한다. 태그만 남기고 digest를 빼지 않는다.
- 로컬에서 `docker build --tag baton-go:local .`과 CI의 `운영 이미지 기동·상태·공개 오류 응답 검증` 성공을 확인한다.

### 병합

PR CI 성공, 위 확인 결과, 남은 위험을 보고하고 사용자가 승인하면 병합한다. 병합·리베이스 요청(`@dependabot rebase`)·
PR 닫기는 원격 상태를 바꾸므로 승인 없이 하지 않는다. 병합 뒤 `main` CI 결과를 확인하고 `HANDOFF.md`에 기록한다.

## 직접 올리는 의존성

### Gradle·Spring Boot

라이브러리 버전은 대부분 루트 `build.gradle`의 Spring Boot 플러그인 BOM이 정한다. 개별 버전 고정보다 BOM 갱신을 우선한다.

1. 버전을 바꾸고 검증 메타데이터를 같은 변경에서 갱신한다. 검증을 끄거나 파일을 지우지 않는다.

   ```bash
   ./gradlew --no-daemon --write-verification-metadata sha256 --refresh-dependencies build
   ```

2. `git diff gradle/verification-metadata.xml`에서 새 컴포넌트의 그룹·이름·버전이 예상한 업그레이드 범위인지 검토한다.
   예상 밖의 그룹이나 다른 저장소에서 온 아티팩트가 있으면 멈추고 보고한다.
3. README 기준대로 `./gradlew --no-daemon --refresh-dependencies build`, `docker build --tag baton-go:local .`,
   `./gradlew --no-daemon :bootstrap:mysqlTest :bootstrap:redisTest`를 실행한다.
4. Spring Boot·Spring Security 마이너 이상 변경은 릴리스 노트의 기본 동작 변경(오류 처리, 변환기, JWT 검증, Actuator 노출)을
   기존 계약 테스트와 대조한다. HANDOFF에 남긴 Spring 기본 동작 의존(예: `InstantFormatter`)도 확인한다.

### MySQL 이미지

MySQL은 Dependabot 대상이 아니다. 같은 이미지 digest를 다음 위치에서 함께 바꾼다.

- `compose.yml`의 `mysql` 서비스 이미지
- `deploy/k8s/overlays/private-server/kustomization.yaml`의 MySQL `digest`
- `deploy/k8s/base/mysql-statefulset.yaml`의 기준 태그(메이저·마이너가 바뀔 때)

Testcontainers 통합 테스트는 `compose.yml` 이미지를 직접 읽는다. CI의 `배포 이미지 다이제스트 계약 검증`으로
Compose·오버레이 일치를, `:bootstrap:mysqlTest`로 실제 기동을 확인한다. 메이저·마이너 변경은
`docs/RUNBOOK/kubernetes-private-server-deployment.md`의 백업·복원 절차와 기존 PVC 기동 검증을 함께 계획한다.

## 커밋

`Chore: <대상> <이전>→<새 버전> 갱신` 형식으로 커밋하고 검증 결과를 `baton-go-wrap-up`에 따라 기록한다.
