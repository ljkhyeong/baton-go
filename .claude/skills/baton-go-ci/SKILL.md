---
name: baton-go-ci
description: BATON GO의 GitHub Actions(CI, 릴리스 검사 자료 보관, 릴리스 취약점 재검사) 실행 결과를 확인하거나 실패 원인을 찾아 고칠 때 사용한다. main push와 PR 실행 모두 대상이다.
argument-hint: "[run ID | PR 번호 | 브랜치]"
---

# BATON GO CI 실패 분석

저장소는 `ljkhyeong/baton-go`다. CI는 PR과 `main` push에서 실행되며, 이 저장소는 주로 `main`에 직접 푸시한다.

## 1. 대상 실행 찾기

`gh auth status`로 인증을 먼저 확인한다. 인증이 없으면 사용자에게 `gh auth login`을 요청하고 멈춘다.

```bash
gh run list --workflow ci.yml --branch main --limit 5     # 인자가 없을 때. 워크플로를 지정해야 Dependabot 실행이 섞이지 않는다
gh pr checks <PR 번호>                                    # PR이면
gh run view <run ID> --json name,conclusion,headSha,event,jobs \
  --jq '{name,conclusion,headSha,event,jobs:[.jobs[]|{name,conclusion,steps:[.steps[]|select(.conclusion=="failure")|.name]}]}'
gh run view <run ID> --log-failed > <로그 경로>
```

- 실패한 실행의 `headSha`가 로컬 `HEAD`와 같은지 확인한다. 다르면 이미 고쳐졌는지 최신 실행부터 본다.
- 로그는 파일로 저장하고 실패 단계 주변만 읽는다. 로그와 산출물에 비밀값이 섞였을 수 있으므로 응답에 원문을 길게 옮기지 않는다.
- 테스트 실패면 `gh run download <run ID> -n baton-go-test-reports -D <임시 디렉터리>`로 JUnit XML·HTML 보고서를 받는다.

## 2. 실패 단계별 재현

CI `필수 검증` 작업의 단계 이름으로 로컬 재현 범위를 고른다. 단계 스크립트가 길면 `.github/workflows/ci.yml`에서
해당 `run` 블록을 읽어 같은 `env` 값으로 실행한다.

| 실패 단계 | 로컬 재현 |
| --- | --- |
| 저장소 체크아웃, Java·Gradle·kubectl·Kubeconform 설정 | 대개 러너·네트워크 문제. 액션 SHA를 바꿨다면 고정값과 버전 주석 확인 |
| Kubernetes Kustomize strict schema 검증, 배포 이미지 다이제스트 계약 검증 | 해당 `run` 블록 (`kubectl kustomize` + `kubeconform`) |
| Prometheus 수집 설정·경보 규칙 검증, 알림 설정·라우팅 검증, 감시 시스템 중단 알림 | 같은 `promtool`·`amtool` 이미지로 `docs/RUNBOOK/prometheus-alerts.md#로컬ci-검증` |
| Discord·Slack·Healthchecks 웹훅 전달 검증 | `python3 tools/verify-webhooks.py --output <임시 디렉터리>` |
| 정적·단위·패키징 검증 | `./gradlew --no-daemon build` 또는 실패 모듈의 `test --tests` |
| 운영 이미지 빌드 검증, 이미지 SBOM·취약점 보고서 생성 | `docker build --tag baton-go:ci .` 후 Trivy 단계. 취약점 발견 자체는 실패 원인이 아니며 실행 오류만 실패시킨다 |
| 운영 이미지 기동·상태·공개 오류 응답 검증 | 이미지 빌드 후 해당 `run` 블록 (Compose 기동, `/readyz`, 공개 오류 HTML·JSON, `429`) |
| MySQL 통합 검증 | `./gradlew --no-daemon :bootstrap:mysqlTest :bootstrap:redisTest` (Docker 필요) |
| 검증한 운영 이미지 GHCR 게시, Java 의존성 알림 연동 | `main` push 전용. 권한(`packages`·`contents: write`)·GitHub API 응답 확인 |
| 게시 이미지 출처 증명 | `main` push 전용. 같은 실행의 게시·검사 산출물 일치, `id-token`·`attestations` 권한, `gh attestation verify` 오류 문구 확인. 절차는 `docs/RUNBOOK/image-security-reports.md#빌드-출처sbom-증명` |

릴리스 워크플로(`release-evidence.yml`, `release-vulnerability-review.yml`)는 `main`에서 수동 실행하며 태그를 입력받는다.
실패하면 태그와 같은 커밋의 성공한 CI 산출물(`baton-go-image-security`, `baton-go-image-publication`)과 게시 이미지 증명이 있는지 먼저 확인하고
`docs/RUNBOOK/image-security-reports.md`의 해당 절을 따른다.

## 3. 원인 분류와 수정

- 코드·테스트·설정 결함과 러너·네트워크·외부 서비스·권한 문제를 구분해 보고한다. 재현되지 않으면 그렇다고 말한다.
- 사용자가 수정을 요청했으면 원인을 고치고 `baton-go-verify`로 같은 범위를 로컬에서 확인한다. 조사만 요청했으면
  실패 단계, 실행 URL, 핵심 로그 몇 줄, 원인 추정과 수정 방안을 보고하고 멈춘다.
- 실패한 작업 재실행(`gh run rerun --failed`), 워크플로 수동 실행, push는 원격 상태를 바꾸므로 사용자 확인 후에만 한다.
- 일시적 실패로 판단해 재실행만 했다면 원인을 바꾸지 않았다는 점을 HANDOFF·보고에 남긴다.
