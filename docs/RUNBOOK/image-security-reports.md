# 이미지 검사·GHCR 게시

## 적용 범위

[CI 워크플로](../../.github/workflows/ci.yml)는 Dockerfile로 만든 운영 이미지를 Trivy로 한 번
검사하고, 같은 JSON 결과를 CycloneDX SBOM·읽기용 표·GitHub 제출 형식으로 변환한다.
SBOM에는 취약점이 발견되지 않은 패키지도 포함한다. 별도 패키지 분석기나 취약점 판정 코드는 두지 않는다.

검사 대상은 해당 실행에서 빌드한 이미지다. 소스 저장소, 실행 중인 컨테이너나 운영 환경 변수는
검사기에 전달하지 않는다. Docker 소켓 대신 읽기 전용 이미지 아카이브를 전달하며,
사용 통계 전송과 버전 확인은 끈다. `--offline-scan`으로 의존성 식별용 외부 API 호출을
제한하지만, 취약점 DB·Java DB와 검사기 이미지를 받으려면 네트워크가 필요하다.
보고서 변환은 네트워크를 차단한 상태에서 수행한다.

## 보존 파일과 검사 대상 확인

GitHub Actions의 해당 실행에서 `baton-go-image-security` 산출물을 내려받는다.
보존 기간은 워크플로의 `retention-days` 설정을 따른다. 이미지 아카이브와 검사 캐시는
업로드하지 않는다.

| 파일 | 내용 |
| --- | --- |
| `image-metadata.json` | 체크아웃한 커밋, Docker 이미지 ID·구성 다이제스트, 검사 아카이브 SHA-256, 플랫폼, 고정된 검사기 이미지 |
| `scanner.json` | Trivy 버전과 사용한 취약점·Java DB 메타데이터 |
| `sbom.cdx.json` | 검사기가 식별한 이미지 구성 요소의 CycloneDX SBOM |
| `vulnerabilities.json` | 패키지 목록과 취약점의 원본 검사 결과 |
| `vulnerabilities.txt` | 검토용 취약점 표 |
| `dependency-graph.json` | 같은 검사에서 추출한 Java 패키지 목록과 소스 커밋·CI 실행 정보. GitHub 의존성 API 제출용 |

`source_commit`은 `git rev-parse HEAD` 값이다. PR 실행에서는 작성자 브랜치의 마지막 커밋이
아니라 CI가 체크아웃한 병합용 커밋일 수 있다. 이미지 태그만 보고 다른 실행의 결과와 혼동하지 않는다.

`image_config_digest`는 Trivy가 아카이브에서 읽은 이미지 구성 객체의 ID이며,
`vulnerabilities.json`의 `Metadata.ImageID`와 같다. `docker_image_id`는 Docker가 반환한
이미지 ID로, 이미지 저장 방식에 따라 인덱스를 가리킬 수 있으므로 구성 다이제스트와 구분한다.
`image_archive_sha256`은 실제 검사한 아카이브 파일의 체크섬이다. 이 값들을 배포용
`repository@sha256:...` 매니페스트 다이제스트 대신 사용하지 않는다. 플랫폼도 함께 확인한다.

## 성공·실패와 취약점 검토

- 모든 심각도와 수정 버전이 아직 없는 취약점을 보고한다. `--exit-code 0`이므로 발견 자체는
  CI 실패 사유가 아니다. 검사 실행, DB 다운로드나 보고서 변환이 실패하면 해당 단계가 실패한다.
- 검사 단계가 실패해도 이미 생성된 보고서는 업로드를 시도한다. 위 파일 중 일부가 빠진 산출물이나
  실패한 CI 실행을 검사 완료 기록으로 사용하지 않는다. 취소된 실행에서는 보존을 보장하지 않는다.
- 먼저 `vulnerabilities.txt`에서 대상 패키지·설치 버전·수정 버전을 확인한다. `HIGH`·`CRITICAL`은
  우선 검토하되, 실제 사용 경로와 영향 범위, 수정 가능 여부를 보고 대응을 정한다.
- 기반 이미지나 의존성을 갱신했다면 새 이미지를 빌드해 다시 검사한다. DB가 갱신되면 같은
  이미지에서도 결과가 달라질 수 있으므로 검사 시각과 `scanner.json`의 DB 정보를 함께 남긴다.
- Java 21 기반 이미지의 업데이트는 [Dependabot](external-api-integrations.md#ci-도구java-이미지-업데이트-제안)이
  PR로 제안한다. PR의 새 이미지와 취약점 보고서를 검토하며, PR 생성만으로 수정 완료로 보지 않는다.
- SBOM과 취약점 없음 판정은 검사기가 식별한 범위에 한정된다. 소스 코드 보안 검토,
  운영 설정 점검, 비밀값 검사나 미공개 취약점 탐지를 대신하지 않는다.

보고서는 의존성과 이미지 구성 정보를 포함하므로 저장소·Actions 산출물의 접근 권한을 따른다.
원본 보고서의 패키지명·CVE·도구 출력은 번역하거나 수정하지 않는다.

`main`의 CI 전체가 성공하면 별도 작업이 `dependency-graph.json`을 GitHub에 제출한다.
PR이나 검증 실패에서는 제출하지 않으며, 제출 실패 시 CI가 실패로 표시된다.
알림 기능의 상태와 확인 방법은 [의존성 알림 연동](external-api-integrations.md#java-라이브러리-취약점-알림)을 따른다.
제출 목록은 이미지에 포함된 Java 패키지이며 Gradle의 전체 의존 관계를 대신하지 않는다.

## 릴리스에서 별도로 확인할 사항

`main` push의 빌드·검사·실행·DB 검증이 통과하면 아래 절차로 이미지를 게시한다.
이미지 서명, 취약점 등급별 배포 차단과 운영 배포는 별도로 수행한다.
게시 성공만으로 공개 운영을 승인하지 않는다.

릴리스 담당자는 실제 배포할 매니페스트 다이제스트와 플랫폼에 맞는 검사 결과를 확보하고,
발견 사항의 처리·예외 승인, 서명·provenance 검증과 보존 위치를 기록한다. 상세 배포 점검 항목은
[비공개 Kubernetes 배포 절차](kubernetes-private-server-deployment.md)를 따른다.

## GHCR 자동 게시와 배포 참조

검증 작업의 마지막 단계가 같은 로컬 이미지를 `ghcr.io/ljkhyeong/baton-go`에 게시한다.
PR·검증 실패에서는 게시하지 않는다. 게시 전 검사 보고서의 이미지 ID·소스 커밋을 대조하며,
재빌드하거나 이미지 아카이브를 Actions 산출물로 올리지 않는다.

- 인증은 임시 `GITHUB_TOKEN`과 검증 작업의 `packages: write` 권한을 사용한다.
  체크아웃에는 인증 정보를 남기지 않고, 게시용 Docker 인증 파일은 단계 종료 시 삭제한다.
- 태그는 `sha-<커밋>-<실행 ID>-<재실행 번호>`다. `latest`나 배포용 고정 태그를 덮어쓰지 않는다.
- 첫 게시의 패키지는 비공개다. `GITHUB_TOKEN`으로 게시하면 저장소와 연결된다.
  이미 같은 이름의 패키지가 있다면 패키지의 Actions 접근 설정에서 이 저장소에 쓰기 권한을 허용한다.
- 성공한 실행은 게시 다이제스트를 실행 요약과 `baton-go-image-publication` 산출물에 남긴다.
  `image-publication.json`에는 검사 메타데이터와 게시 태그·다이제스트·실행 주소가 들어 있다.
  이 파일은 서명된 provenance가 아니다.

원격 `main`에 반영한 뒤 첫 게시 성공과 패키지 접근 권한을 확인한다. 같은 실행의
`baton-go-image-security`와 `baton-go-image-publication`을 함께 검토하고 릴리스 기록으로 보관한다.
Actions 산출물은 14일 뒤 만료되며, 게시된 패키지의 보관 기간과는 별개다.
릴리스로 남길 버전은 아래 보관 워크플로로 보고서를 Release 첨부 파일에 옮긴다.

배포 오버레이의 `newName`은 `ghcr.io/ljkhyeong/baton-go`로, `digest`는
`image-publication.json`의 `image_reference`에서 `@` 뒤 값으로 교체한다.
현재 예시의 다이제스트 자리표시는 첫 릴리스 검토 전까지 유지한다.
애플리케이션과 Flyway Job은 같은 참조를 쓰며 노드 플랫폼도 확인한다.
현재 CI 이미지는 `linux/amd64`다. ARM 홈서버에는 그대로 사용하지 않는다.

홈서버에서 비공개 이미지를 받을 때는 `read:packages` 권한의 PAT classic을 기존
`baton-go-registry` imagePullSecret에 연결한다. 게시 권한이나 저장소 전체 권한은 추가하지 않는다.
토큰·Secret 값은 저장소와 명령행에 남기지 않고
[기존 Secret 준비 절차](kubernetes-private-server-deployment.md)를 따른다.

GHCR의 컨테이너 이미지 저장·전송은 현재 무료다. CI 실행 시간과 보고서 보존은 기존 Actions 사용량에
포함되므로 무료 할당량과 유료 사용 차단 설정을 유지한다. 유료 실행기나 추가 저장 서비스는 쓰지 않는다.

## 릴리스 검사 자료 보관

[보관 워크플로](../../.github/workflows/release-evidence.yml)는 기존 Git 태그를 선택하면 같은 커밋의
성공한 `main` CI 자료를 받아 GitHub Release 초안을 만든다. 이미지 빌드·검사는 반복하지 않는다.

1. 원격 `main` CI의 전체 성공과 GHCR 게시를 확인하고 해당 커밋에 릴리스 태그를 등록한다.
2. Actions의 `릴리스 검사 자료 보관`에서 실행 브랜치를 `main`으로 두고 기존 태그를 입력한다.
   아직 Release가 없는 태그를 사용하며, CI 자료가 만료되기 전인 14일 안에 실행한다.
3. 만들어진 초안에서 소스 커밋·CI 실행·배포 이미지와 첨부 자료를 검토한 뒤 Release를 발행한다.
   비공개 저장소의 접근 권한은 유지된다. 워크플로가 운영 배포나 이미지 서명을 수행하지는 않는다.

초안에는 다음 두 파일을 첨부한다.

| 파일 | 내용 |
| --- | --- |
| `baton-go-release-evidence.tar.gz` | CI 실행 정보, SBOM·취약점·검사기·Java 의존성 보고서, 검사·게시 메타데이터 |
| `image-publication.json` | 배포 이미지 다이제스트·플랫폼·소스 커밋을 바로 확인할 게시 기록 |

같은 커밋의 성공한 `CI` 중 최신 실행을 선택하고 검사·게시 자료의 이미지 정보와 재실행 번호를
대조한다. 성공한 실행이 없거나 산출물이 만료·누락됐거나 두 기록이 다르면 초안 생성 전에 실패한다.
기존 Release가 있는 태그도 중단한다. 업로드 도중 실패한 초안은 남을 수 있으므로 첨부 상태를
확인한 뒤 이 워크플로가 만든 미완성 초안만 정리하고 재실행한다. 발행한 Release는 삭제하지 않는다.

Release 첨부 파일은 Actions의 14일 보관 설정을 적용받지 않는다. 파일당 2GiB 미만,
릴리스당 1,000개 한도를 따르며 전체 릴리스 크기와 다운로드 대역폭에는 별도 제한이 없다.
보관 워크플로의 실행 시간은 기존 Actions 사용량에 포함된다.

근거: [GitHub Release 보관 한도](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases),
[CI 산출물 받기](https://cli.github.com/manual/gh_run_download),
[Release 초안·첨부 생성](https://cli.github.com/manual/gh_release_create).

## 릴리스 취약점 재검사

[재검사 워크플로](../../.github/workflows/release-vulnerability-review.yml)는 Release에 보관한
Trivy SBOM을 최신 취약점 DB로 다시 검사한다. 코드 변경 없이 새로 공개된 운영체제·Java
라이브러리 취약점을 확인할 때 사용한다. 이미지 빌드·수신과 홈서버 접속은 필요하지 않다.

1. 위 보관 워크플로로 대상 태그의 검사 자료를 Release에 첨부한다. 초안도 지정할 수 있다.
2. Actions의 `릴리스 취약점 재검사`에서 실행 브랜치를 `main`으로 두고 대상 태그를 입력한다.
3. 성공한 실행의 `baton-go-release-vulnerabilities` 산출물을 받는다. `vulnerabilities.txt`의
   대상 패키지·설치 버전·수정 버전을 검토하고 실제 배포 이미지 다이제스트와 대조한다.

Actions 화면의 실행 커밋은 워크플로를 읽은 `main`이다. 검사 대상은 입력한 태그이며,
`rescan-metadata.json`의 `source_commit`과 `image_reference`로 확인한다.

태그의 커밋과 게시 기록, SBOM의 이미지 구성 다이제스트를 대조한다. 자료가 누락됐거나
서로 다른 이미지를 가리키거나 SBOM이 비어 있으면 검사 전에 실패한다.
등록된 패키지의 취약점 판정은 Trivy가 처리하며, 이미지나 저장소 내용을 외부 분석 API로 보내지 않는다.
사용 통계와 의존성 식별용 외부 호출은 끄지만, 검사기 이미지·취약점 DB 다운로드에는 네트워크가 필요하다.
CI와 재검사 워크플로의 `TRIVY_IMAGE`는 함께 갱신한다.

| 파일 | 내용 |
| --- | --- |
| `rescan-metadata.json` | 릴리스 태그·소스 커밋·배포 이미지·원본 SBOM 체크섬·검사기·완료 시각·이번 실행 정보 |
| `image-publication.json` | 원래 CI의 게시 기록 |
| `scanner.json` | 이번 검사에 사용한 Trivy·취약점 DB 정보 |
| `vulnerabilities.json`·`vulnerabilities.txt` | 이번 검사의 전체 패키지·취약점 결과와 읽기용 표 |

기존 정책처럼 취약점 발견 자체는 실행 실패로 처리하지 않는다. DB 다운로드나 검사·변환이
실패한 실행은 완료 기록으로 사용하지 않는다. 원본 SBOM에 빠진 패키지는 재검사로 보완할 수 없으며,
실제 이미지가 바뀌었다면 새 CI 검사 자료가 필요하다.

수동 실행만 제공하고 기존 Actions 사용량·14일 산출물 보관 한도를 적용한다. 별도 유료 API·계정은
필요하지 않다. 원본 Release와 게시 이미지는 변경하지 않으며, 재검사 결과를 장기 보관하려면
산출물이 만료되기 전에 담당자가 보관한다. 기존 `CI` 전용 Slack 구독에는 이 실행이 포함되지 않는다.

근거: [Trivy SBOM 검사](https://trivy.dev/docs/latest/target/sbom/),
[Release 자료 다운로드](https://cli.github.com/manual/gh_release_download).

## 표준 도구 문서

- [Trivy 이미지 아카이브 검사](https://trivy.dev/docs/latest/target/container_image/)
- [Trivy 보고서 변환](https://trivy.dev/docs/latest/configuration/reporting/#converting)
- [Trivy SBOM 생성](https://trivy.dev/docs/latest/supply-chain/sbom/)
- [GitHub Actions 이미지 게시](https://docs.github.com/en/actions/tutorials/publish-packages/publish-docker-images)
- [GHCR 인증·접근 권한](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
- [GitHub Packages 요금](https://docs.github.com/en/billing/concepts/product-billing/github-packages)
