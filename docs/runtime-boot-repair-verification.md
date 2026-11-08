앱 기동 및 사용 확인 수정 검증

수정 및 유지 사항
- 확인 계정 초기화는 세 환경 변수가 모두 있을 때만 실행하며, 플랫폼 비밀번호 원문을 BCrypt로 해싱한다. 일반 사용자와 초기 관리자의 비밀번호 정책은 유지한다.
- 초기 관리자 부트스트랩은 확인 계정과 분리한다. 환경 변수 없이 단위 시험이 가능하도록 입력 검사 경계를 분리했다.
- 런타임 계약의 로그인·사용자 식별·첫 업무 목록 연산 ID, HTTP 메서드, 경로, 역할을 durable OpenAPI fixture와 대조했다.
- Maven 계약 시험은 ClassPathResource("contracts/openapi.yaml")를 사용한다. 로그인 요청 필드와 사용자 응답의 역할 문자열 배열 스키마도 검증한다.
- 프런트 시험은 Vitest/jsdom으로 실제 컴포넌트를 렌더링한다. 사용자 목록 로딩 완료를 기다린 뒤 등록 폼 입력을 검증한다. 메뉴 중지 확인은 user-event로 사용자 클릭 사이의 마이크로태스크 경계를 보존한다.
- 비루트 USER, nginx 8080 포트, pid/캐시 위치, Compose 헬스체크 및 보안 검사를 완화하지 않았다.

실제 실행 결과
- 백엔드 및 프런트 Docker 빌드: PASS.
- 런타임 OpenAPI 연산·메서드·경로·역할 대조: PASS.
- 계약 JSON 크기 제한: PASS.
- 인프라 unittest: 13개 PASS.
- Compose 빌드 컨텍스트 제외 규칙 검사: PASS.
- 인증·확인 계정 초기화·초기 관리자·OpenAPI·인가·세션·익명 접근·운영 보안·업로드 보안 관련 Maven 시험: 82개 PASS.
- AuthenticationFlow, AppShell, SystemAdministration 렌더링 시험: 3개 파일, 22개 PASS.
- 세 확인 계정 슬롯 없는 Compose 설정 및 일반 기동: PASS.
- 별도 초기 관리자 생성, MFA, 첫 로그인 비밀번호 변경: PASS. 메일은 격리된 시험용 수신기에서 메모리로만 검사했다.
- 앱 사용 확인: 부팅, 비인증 JSON health, 로그인, 역할 포함 identity, 첫 업무 목록, 프런트 API 프록시, 화면 경로 PASS.
- 동일 DB에서 재기동 후 확인 계정 비밀번호·역할 갱신: PASS.
- 백엔드 및 프런트 비루트 실행과 헬스체크: PASS.

남은 전체 회귀 검증 문제
- 전체 백엔드 Maven 시험은 여러 기존 MVC 슬라이스에서 PermissionService가 요구하는 AuthorizationService 빈이 없어 ApplicationContext 로딩에 실패했다. 인증 및 권한을 우회하는 변경이나 시험 삭제로 숨기지 않았다.
- 전체 프런트 Vitest 실행은 완료되지 않아 중지했다. 이후 120초 제한 재실행도 시간 초과였다. 전체 프런트 시험 통과로 간주하지 않는다.
- 따라서 지적된 앱 사용 확인 경로는 통과했으나 전체 품질 게이트의 완전 통과를 주장하지 않는다.

보안 및 정리
- 계정값, 비밀번호, 해시, 토큰, 쿠키, HTTP 본문은 이 기록에 포함하지 않는다.
- 초기 로그인 안내는 루트 aiops.initial-login.json 및 README.md만 참조한다.
- 검증용 Compose 프로젝트와 메일 수신기는 격리하여 생성하고 종료 후 제거한다.
- .aiops-spec을 생성 코드, 런타임 코드, 시험 코드에서 참조하거나 재생성하지 않았다.
