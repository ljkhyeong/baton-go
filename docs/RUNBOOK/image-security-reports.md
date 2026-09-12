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

## 표준 도구 문서

- [Trivy 이미지 아카이브 검사](https://trivy.dev/docs/latest/target/container_image/)
- [Trivy 보고서 변환](https://trivy.dev/docs/latest/configuration/reporting/#converting)
- [Trivy SBOM 생성](https://trivy.dev/docs/latest/supply-chain/sbom/)
- [GitHub Actions 이미지 게시](https://docs.github.com/en/actions/tutorials/publish-packages/publish-docker-images)
- [GHCR 인증·접근 권한](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
- [GitHub Packages 요금](https://docs.github.com/en/billing/concepts/product-billing/github-packages)
