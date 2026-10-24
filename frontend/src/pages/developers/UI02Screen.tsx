import { useCallback, useEffect, useState, type ReactNode } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import {
  Badge,
  Button,
  Pagination,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import { ScreenHeading } from "../shared/ScreenHeading";
import {
  DeveloperDrawer,
  DeveloperStatusBadge,
  type DeveloperDetailTab,
} from "./DeveloperDrawer";
import { useDeveloperReadOnly } from "./useDeveloperReadOnly";
import UI32Screen from "./UI32Screen";
import {
  defaultDeveloperFilters,
  developerStatuses,
  getDeveloper,
  listDevelopers,
  type DeveloperDetail,
  type DeveloperFilters,
  type DeveloperListItem,
} from "./client";
import styles from "./Developers.module.css";

export interface DevelopersScreenProps {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
  detailTabs?: DeveloperDetailTab[];
  onDisable?: (rows: DeveloperListItem[]) => void;
  onUpload?: (form: "B.8" | "B.1") => void;
  onExport?: (filters: DeveloperFilters, ids: number[]) => void;
  dialogs?: ReactNode;
  refreshVersion?: number;
}
// SCR-UI-02: kept mounted by AppShell for both /UI-02 and its /UI-32 child.
export default function UI02Screen({
  name = "개발자 풀",
  groupName = "기준정보",
  onDirtyChange,
  detailTabs,
  onDisable,
  onUpload,
  onExport,
  dialogs,
  refreshVersion = 0,
}: DevelopersScreenProps) {
  const location = useLocation(),
    navigate = useNavigate(),
    readOnly = useDeveloperReadOnly();
  const [registration, setRegistration] = useState(
    location.pathname === "/UI-32",
  );
  useEffect(() => {
    if (location.pathname === "/UI-32" || location.pathname === "/UI-02")
      setRegistration(location.pathname === "/UI-32");
  }, [location.pathname]);
  const [draft, setDraft] = useState(defaultDeveloperFilters),
    [filters, setFilters] = useState(defaultDeveloperFilters);
  const [selected, setSelected] = useState<number[]>([]),
    [detailId, setDetailId] = useState<number | null>(null),
    [highlight, setHighlight] = useState<DeveloperDetail | null>(null),
    [message, setMessage] = useState("");
  const employees = useCodes("EMP_TYPE"),
    jobs = useCodes("JOB");
  const read = useCallback(
      () => listDevelopers(filters),
      [filters, refreshVersion],
    ),
    list = useAdminRead(read);
  const refresh = useCallback(
    async (text: string) => {
      const page = await list.reload();
      if (highlight) setHighlight(await getDeveloper(highlight.dev_id));
      setMessage(text);
      return page;
    },
    [list.reload, highlight],
  );
  const created = (location.state as { developerCreated?: number } | null)
    ?.developerCreated;
  useEffect(() => {
    if (created && !registration) {
      setFilters(defaultDeveloperFilters);
      setDraft(defaultDeveloperFilters);
      setSelected([]);
      void getDeveloper(created)
        .then((row) => {
          setHighlight(row);
          setMessage("저장했습니다.");
        })
        .catch(() => {});
      void list.reload().catch(() => {});
    }
  }, [created, registration]);
  const reset = () => {
    setDraft(defaultDeveloperFilters);
    setFilters({ ...defaultDeveloperFilters });
    setHighlight(null);
    setSelected([]);
  };
  const search = () => {
    setFilters({ ...draft, page: 0 });
    setHighlight(null);
    setSelected([]);
  };
  const changePage = (page: number) => {
    setFilters((f) => ({ ...f, page }));
    setHighlight(null);
    setSelected([]);
  };
  const sort = (key: string) => {
    const order = filters.sort === `${key},asc` ? "desc" : "asc";
    setFilters((f) => ({ ...f, sort: `${key},${order}`, page: 0 }));
    setHighlight(null);
    setSelected([]);
  };
  const rows =
    highlight && filters.page === 0
      ? [
          highlight,
          ...(list.data?.items ?? []).filter(
            (r) => r.dev_id !== highlight.dev_id,
          ),
        ]
      : (list.data?.items ?? []);
  const toggle = (id: number) =>
    setSelected((s) =>
      s.includes(id) ? s.filter((v) => v !== id) : [...s, id],
    );
  const columns = [
    ["dev_nm", "이름"],
    ["mng_no", "관리번호"],
    ["affiliation_name", "소속"],
    ["emp_type_name", "직원구분"],
    ["grade_name", "등급"],
    ["main_job_name", "주 직무"],
    ["status", "상태"],
    ["current_project_name", "현재 프로젝트 / 이번 달 MM"],
    ["latest_assignment_end_dt", "철수 예정일"],
    ["dflt_untprc_amt", "월 단가"],
  ];
  return (
    <section data-testid="developer-workspace" data-screen-id="SCR-UI-02">
      <div hidden={registration}>
        <ScreenHeading
          screenId="UI-02"
          name={name}
          groupName={groupName}
          actions={
            !readOnly && (
              <div className={styles.writeActions}>
                <Can perm="UI-02.download">
                  <a
                    data-testid="developer-template"
                    className={styles.linkButton}
                    href="/api/uploads/templates/B.8"
                  >
                    양식 내려받기
                  </a>
                </Can>
                <Can perm="UI-02.upload">
                  <Button
                    data-testid="developer-upload"
                    disabled={!onUpload}
                    onClick={() => onUpload?.("B.8")}
                  >
                    엑셀 업로드
                  </Button>
                  <Button
                    data-testid="developer-salary-upload"
                    disabled={!onUpload}
                    onClick={() => onUpload?.("B.1")}
                  >
                    급여 업로드
                  </Button>
                </Can>
                <Can perm="UI-02.create">
                  <Button
                    data-testid="developer-create"
                    variant="primary"
                    onClick={() => navigate("/UI-32")}
                  >
                    + 등록
                  </Button>
                </Can>
              </div>
            )
          }
        />
        <form
          className={styles.search}
          onSubmit={(e) => {
            e.preventDefault();
            search();
          }}
        >
          <label>
            직원구분
            <select
              data-testid="developer-employee-filter"
              aria-label="조회 직원구분"
              value={draft.empType}
              onChange={(e) =>
                setDraft((d) => ({ ...d, empType: e.target.value }))
              }
            >
              <option value="">전체</option>
              {employees.codes.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            직무
            <select
              data-testid="developer-job-filter"
              aria-label="조회 직무"
              value={draft.job}
              onChange={(e) => setDraft((d) => ({ ...d, job: e.target.value }))}
            >
              <option value="">전체</option>
              {jobs.codes.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            상태
            <select
              data-testid="developer-status-filter"
              aria-label="조회 상태"
              value={draft.status}
              onChange={(e) =>
                setDraft((d) => ({ ...d, status: e.target.value }))
              }
            >
              <option value="">전체</option>
              {developerStatuses.map((s) => (
                <option key={s}>{s}</option>
              ))}
            </select>
          </label>
          <label>
            이름·관리번호
            <input
              data-testid="developer-search"
              aria-label="이름·관리번호"
              value={draft.q}
              onChange={(e) => setDraft((d) => ({ ...d, q: e.target.value }))}
            />
          </label>
          <label>
            조회 범위
            <select
              data-testid="developer-use-filter"
              aria-label="조회 범위"
              value={draft.use_yn}
              onChange={(e) =>
                setDraft((d) => ({ ...d, use_yn: e.target.value as "Y" | "N" }))
              }
            >
              <option value="Y">사용 중</option>
              <option value="N">사용 중지자</option>
            </select>
          </label>
          <Button data-testid="developer-reset" type="button" onClick={reset}>
            초기화
          </Button>
          <Button data-testid="developer-query" type="submit" variant="primary">
            조회
          </Button>
        </form>
        <ErrorBanner error={employees.error} onRetry={employees.retry} />
        <ErrorBanner error={jobs.error} onRetry={jobs.retry} />
        <ErrorBanner
          error={list.error}
          onRetry={() => void list.reload().catch(() => {})}
        />
        <div className={styles.toolbar}>
          <span>
            총 {(list.data?.total ?? 0).toLocaleString("ko-KR")}건{" "}
            {selected.length > 0 && `${selected.length}건 선택`}
          </span>
          <div className={styles.actions}>
            {!readOnly && selected.length > 0 && (
              <Can perm="UI-02.delete">
                <Button
                  data-testid="developer-disable-selected"
                  disabled={!onDisable}
                  onClick={() =>
                    onDisable?.(rows.filter((r) => selected.includes(r.dev_id)))
                  }
                >
                  사용 중지
                </Button>
              </Can>
            )}
            <Can perm="UI-02.download">
              <Button
                data-testid="developer-export"
                disabled={!onExport}
                onClick={() => onExport?.(filters, selected)}
              >
                엑셀 다운로드
              </Button>
            </Can>
          </div>
        </div>
        {list.loading && !list.data ? (
          <Skeleton />
        ) : (
          <div className={styles.tableScroll}>
            <table className={styles.table}>
              <thead>
                <tr>
                  {!readOnly && (
                    <th>
                      <input
                        data-testid="developer-select-all"
                        type="checkbox"
                        aria-label="전체 선택"
                        checked={
                          rows.length > 0 &&
                          rows.every((r) => selected.includes(r.dev_id))
                        }
                        onChange={(e) =>
                          setSelected(
                            e.target.checked ? rows.map((r) => r.dev_id) : [],
                          )
                        }
                      />
                    </th>
                  )}
                  {columns.map(([key, label]) => (
                    <th
                      key={key}
                      aria-sort={
                        filters.sort === `${key},asc`
                          ? "ascending"
                          : filters.sort === `${key},desc`
                            ? "descending"
                            : "none"
                      }
                    >
                      <button
                        data-testid={`developer-sort-${key.replaceAll("_", "-")}`}
                        type="button"
                        onClick={() => sort(key)}
                      >
                        {label}
                      </button>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr
                    key={row.dev_id}
                    data-testid={`developer-row-${row.dev_id}`}
                    className={
                      highlight?.dev_id === row.dev_id ? styles.highlight : ""
                    }
                    onClick={() => setDetailId(row.dev_id)}
                  >
                    {!readOnly && (
                      <td onClick={(e) => e.stopPropagation()}>
                        <input
                          data-testid={`developer-select-${row.dev_id}`}
                          aria-label={`${row.dev_nm} 선택`}
                          type="checkbox"
                          checked={selected.includes(row.dev_id)}
                          onChange={() => toggle(row.dev_id)}
                        />
                      </td>
                    )}
                    <td>
                      <button
                        data-testid={`developer-open-${row.dev_id}`}
                        className={styles.textButton}
                        onClick={(e) => {
                          e.stopPropagation();
                          setDetailId(row.dev_id);
                        }}
                      >
                        {row.dev_nm}
                      </button>
                    </td>
                    <td>{row.mng_no}</td>
                    <td>{row.affiliation_name}</td>
                    <td>
                      <Badge>{row.emp_type_name}</Badge>
                    </td>
                    <td>{row.grade_name ?? "–"}</td>
                    <td>{row.main_job_name}</td>
                    <td>
                      <DeveloperStatusBadge status={row.status} />
                    </td>
                    <td>
                      <span>
                        {row.current_project_name
                          ? `${row.current_project_name}${row.current_assignment_count > 1 ? ` 외 ${row.current_assignment_count - 1}건` : ""}`
                          : "–"}
                      </span>
                      <br />
                      <span className={styles.numeric}>
                        {Number(row.current_month_mm).toFixed(2)} MM
                      </span>{" "}
                      {row.over_one_mm && (
                        <Badge tone="warning">1.0 초과</Badge>
                      )}
                    </td>
                    <td>{row.latest_assignment_end_dt ?? "–"}</td>
                    <td className={styles.amount}>
                      {row.dflt_untprc_amt?.toLocaleString("ko-KR") ?? "–"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!list.error && !list.loading && rows.length === 0 && (
              <div className={styles.empty}>
                <p>조건에 맞는 결과가 없습니다.</p>
                <Button data-testid="developer-empty-reset" onClick={reset}>
                  조건 초기화
                </Button>
              </div>
            )}
          </div>
        )}
        <Pagination
          page={filters.page + 1}
          size={filters.size}
          total={list.data?.total ?? 0}
          onPage={(p) => changePage(p - 1)}
          onSize={(size) => {
            setFilters((f) => ({ ...f, size, page: 0 }));
            setHighlight(null);
          }}
        />
        {detailId != null && (
          <DeveloperDrawer
            key={detailId}
            id={detailId}
            refreshVersion={refreshVersion}
            onClose={() => setDetailId(null)}
            onSaved={refresh}
            onDirtyChange={onDirtyChange}
            tabs={detailTabs}
            onDisable={onDisable ? (r) => onDisable([r]) : undefined}
          />
        )}
        {dialogs}
      </div>
      {registration && (
        <UI32Screen
          onDirtyChange={onDirtyChange}
          onSaved={(row) => setHighlight(row)}
        />
      )}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
    </section>
  );
}
