# 이미지 SBOM·취약점 보고서 확인

## 적용 범위

[CI 워크플로](../../.github/workflows/ci.yml)는 Dockerfile로 만든 운영 이미지를 Trivy로 한 번
검사하고, 같은 JSON 결과를 CycloneDX SBOM과 읽기용 표로 변환한다. SBOM에는 취약점이
발견되지 않은 패키지도 포함한다. 별도 패키지 분석기나 취약점 판정 코드는 두지 않는다.

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
- 검사 단계가 실패해도 이미 생성된 보고서는 업로드를 시도한다. 다섯 파일 중 일부가 빠진 산출물이나
  실패한 CI 실행을 검사 완료 기록으로 사용하지 않는다. 취소된 실행에서는 보존을 보장하지 않는다.
- 먼저 `vulnerabilities.txt`에서 대상 패키지·설치 버전·수정 버전을 확인한다. `HIGH`·`CRITICAL`은
  우선 검토하되, 실제 사용 경로와 영향 범위, 수정 가능 여부를 보고 대응을 정한다.
- 기반 이미지나 의존성을 갱신했다면 새 이미지를 빌드해 다시 검사한다. DB가 갱신되면 같은
  이미지에서도 결과가 달라질 수 있으므로 검사 시각과 `scanner.json`의 DB 정보를 함께 남긴다.
- SBOM과 취약점 없음 판정은 검사기가 식별한 범위에 한정된다. 소스 코드 보안 검토,
  운영 설정 점검, 비밀값 검사나 미공개 취약점 탐지를 대신하지 않는다.

보고서는 의존성과 이미지 구성 정보를 포함하므로 저장소·Actions 산출물의 접근 권한을 따른다.
원본 보고서의 패키지명·CVE·도구 출력은 번역하거나 수정하지 않는다.

## 릴리스에서 별도로 확인할 사항

이 CI는 이미지를 레지스트리에 게시하거나 서명하지 않으며, 취약점 등급별 배포 차단 정책도
결정하지 않는다. 로컬 빌드 이미지의 보고서만으로 공개 운영을 승인하지 않는다.

릴리스 담당자는 실제 배포할 매니페스트 다이제스트와 플랫폼에 맞는 검사 결과를 확보하고,
발견 사항의 처리·예외 승인, 서명·provenance 검증과 보존 위치를 기록한다. 상세 배포 점검 항목은
[비공개 Kubernetes 배포 절차](kubernetes-private-server-deployment.md)를 따른다.

## 표준 도구 문서

- [Trivy 이미지 아카이브 검사](https://trivy.dev/docs/latest/target/container_image/)
- [Trivy 보고서 변환](https://trivy.dev/docs/latest/configuration/reporting/#converting)
- [Trivy SBOM 생성](https://trivy.dev/docs/latest/supply-chain/sbom/)
