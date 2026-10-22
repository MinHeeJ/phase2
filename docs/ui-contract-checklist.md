UI 구현 체크리스트

이번 UI 개선 전 대조 (61fe263778c1499693304a9597cee2a3)
- 위 route별 YEAR/YEAR_CUM/MONTH/NONE 계약과 API 메뉴 기반 추가 route를 유지한다.
- 공통 shell: 메뉴/탭/검색은 실제 route 이동, active·접힘·모바일 제한을 표시한다.
- 코드: 좌우 비교형, 그룹 선택→전체 조회→허용 필드 편집→전체 PUT→재조회.
  새 행 identity는 저장된 행과 분리하고 다른 그룹 조회 실패 시 이전 그룹 draft를 노출하지 않는다.
- 그룹/대응 창: 필수·길이·field 오류·처리중 차단, 성공한 경우만 닫고 목록 갱신.
- 기준값: 값/월만 PUT, 마지막 마감월 조회 실패 시 저장 금지·재시도 제공.
  적용이력은 선택 key만 표시하고 늦게 도착한 이전 key 응답은 무시한다.
- 이력: 읽기 전용, 조건/Enter/초기화·서버 total·페이지 유지, 성공 재조회 시 과거 오류 제거.
- 모든 데이터 region: skeleton/empty/error/retry, 업무 데이터와 UI 안내를 구별한다.
- 레퍼런스: 이번 실행에서도 HTTP empty reply·직접 DNS 실패·localhost 접속 거절.
  ui-style.md는 관찰 실패 기록이므로 ui-design.md의 확정 토큰을 적용하며 픽셀 일치를 주장하지 않는다.

개선 후 대조
- 명세 자리 route 각각을 jsdom으로 렌더링하고 제목/안내/동일 경로/업무 저장 없음 확인.
- UI-27 제목줄 그룹 추가→창→취소, 대시보드 이동 후 해당 CTA 비노출 확인.
- BIZ 전체 PUT·실패 draft 유지, SYSTEM 필드 제한은 기존 렌더 시험으로 유지.
- 신규 중복 코드 수정 가능, 다른 그룹 조회 실패 시 이전 행 비노출·저장 차단 확인.
- 마감 조회 재시도 후 min 월/저장 복구, 기준값 적용 이력 응답 경합 회귀 시험 추가.
- API 및 persistence 코드는 변경하지 않음. 실제 DB/브라우저 통합 검증은 별도 제한 기록 참조.

모든 화면: 헤더 → 2단 메뉴 → PC 탭줄 → breadcrumb/제목/즐겨찾기 → 기간 → 본문.
메뉴명/부모/아이콘/모바일 여부는 메뉴 API 정본. 업무 내용은 UI-27만 구현.

route                    목적/유형                  CTA/필드/제약
/UI-01 /UI-03 /UI-04      YEAR 자리                  연도 이전/다음, 별, 업무 입력 없음
/UI-05 /UI-10 /UI-11      YEAR 자리                  같은 계약; UI-10 모바일 PC 안내
/UI-12 /UI-13 /UI-17      YEAR 자리                  화면별 선택 연도 유지
/UI-21 /UI-28            YEAR 자리                  같은 계약
/UI-07 /UI-08 /UI-09     YEAR_CUM 자리              연도/월 변경, 1~선택월 누계
/UI-18                   YEAR_CUM 자리              같은 계약
/UI-14 /UI-19 /UI-20     MONTH 자리                 월 변경/이동, API 마감 상태, CLOSED 잠금 띠
/UI-16                   대시보드 자리              로고 목적지, 닫기 없음, KPI 없음
/UI-22                   명세 명시 자리             로그인 폼 없음; SQL 메뉴 시드에는 없음
/UI-23 /UI-24 /UI-25     NONE 자리                  모바일 PC 안내
/UI-02 /UI-15 /UI-26     SQL 메뉴의 NONE 자리       업무 입력 없음
/UI-29 /UI-30 /UI-31     SQL 메뉴의 NONE 자리       업무 입력 없음
/UI-32 /UI-33            SQL 메뉴의 NONE 자리       업무 입력 없음
/UI-27                   공통코드 좌우 편집         아래 지역 계약

UI-27 지역
코드: 전체 그룹/전체 페이지 수집 → 첫 그룹 선택 → 전체 코드 조회. 기존 dtl_cd 불변.
BIZ만 추가/날짜/추가값/사용 변경. SYSTEM 이름/순서만. attrN_nm이 있는 열만 표시.
추가 기본 순서=max+1. PUT에는 CodeSaveRow 필드만 포함, 작성자/갱신시각 제외.
한 행 오류 시 전체 draft 유지, field 오류와 첫 필드 focus. 저장 후 재조회/성공, 취소 복원.
그룹 창: 코드/이름/추가값 이름만 POST. BIZ/순서는 서버 결정. 성공 후 그룹 재조회.
대응: 표기/대상은 생성 때만; 그룹 API와 useCodes로 활성 코드 선택.
기존 PATCH는 map_role_cd/rmrk_desc/use_yn만. UI trigger 미정이므로 안전한 읽기전용
대응 목록을 유지하고 PATCH 구현은 API에서 제공한다. 신규 대응 추가는 구현한다.
기준값: 현재 값 읽기전용, 변경 창은 새 값/적용월만 PUT. identity/name/unit/source 제외.
마감 조회는 전체 지원 월 범위(0001-01~9999-12)로 마지막 CLOSED 확인 후 min 월 설정.
적용 이력은 선택 key 전체 페이지를 최신순으로 하단에 표시.
변경 이력: 그룹 조건/조회/초기화/Enter, 기본50/20/50/100, total/페이지, 읽기전용.
공통: loading/empty/error/retry/success. 권한 자리 항상 허용, 임의403/로그인 없음.
미저장 그룹/지역/route 이동 확인, beforeunload, 처리중 재클릭 불가.

미해결
ui-style.md는 미관찰 상태 문서이며 참조 HTTP는 현재 empty reply/DNS 실패.
정확한 visual 구조/animation 충족을 주장하지 않는다. 명시된 제품 토큰만 적용한다.
대응 편집 확정 trigger OQ-UI-004는 새 버튼/자동 PATCH 금지 때문에 보류.
페이지는 임시 구현 선택으로 1기반/기본50, 정렬은 기본 정렬만 허용한다.
UI-22는 원본 메뉴에는 없으나 명시 route를 지원한다. SQL 메뉴에 임의 seed 추가 없음.
