# HTTP 계약 조각

BATON GO의 HTTP 동작 정본은 [API 계약](../PRD/0002_api-contract/spec.md)이다. 이 문서는
정본을 반복하지 않고, 기존 MockMvc 계약 테스트가 생성하는 실행 가능한 요청·응답 조각의
위치와 생성 방법만 안내한다.

다음 명령은 링크 생성·재생·관리 조회·폐기, 공개 `GET·HEAD`, 관리 인증 실패와 대상 계약
운영 API의 대표 요청·응답을 생성해 하나의 압축 파일로 묶는다.

```bash
./gradlew :adapter-in-web:apiContractDocs
```

생성 결과는 다음 위치에 있다.

```text
adapter-in-web/build/distributions/baton-go-rest-docs.zip
```

CI의 `필수 검증` 작업은 같은 파일을 `baton-go-rest-docs` 산출물로 14일간 보존한다.
산출물이 없으면 CI가 실패하므로 계약 테스트와 압축 작업이 실행되지 않은 상태를 성공으로
처리하지 않는다.

각 작업 식별자는 안정적인 디렉터리 이름으로 유지한다.

- `links-create`
- `links-create-replay`
- `links-get`
- `links-revoke`
- `links-resolve`
- `links-resolve-head`
- `management-authentication-required`
- `target-contract-inventory`
- `target-contract-remediation`

생성 조각의 관리 `Authorization` 값은 실제 테스트 자격 증명 대신
`Bearer <management-token>` 또는 `Bearer <invalid-management-token>`으로 치환한다. 실제
비밀값, 원문 링크 코드와 환경별 전체 단축 URL을 계약 산출물에 넣지 않는다.
