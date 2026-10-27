import { useCallback, useEffect, useState, type ReactNode } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import {
  Button,
  Badge,
  ConfirmDialog,
  Pagination,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { useAdminRead } from "../admin/useAdminResource";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import { useCodes } from "../shared/useCodes";
import { listProjectOptions } from "../projects/client";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import {
  assignmentDefaults,
  listAssignments,
  type AssignmentDetail,
  type AssignmentFilters,
  type AssignmentItem,
} from "./client";
import { UI12M1 } from "./UI12M1";
import { UI12DetailPanel } from "./UI12DetailPanel";
import styles from "./Assignments.module.css";

export interface AssignmentScreenContext {
  filters: AssignmentFilters;
  readOnly: boolean;
  refresh: () => Promise<unknown>;
  openAssignment: (id: number) => void;
  onDirtyChange: (dirty: boolean) => void;
}
export interface AssignmentsScreenProps {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
  initialProjectId?: number;
  initialAssignmentId?: number;
  toolbar?: ReactNode;
  dialogs?: ReactNode;
  detailActions?: (
    row: AssignmentDetail,
    context: AssignmentScreenContext,
  ) => ReactNode;
  detailPanels?: (
    row: AssignmentDetail,
    context: AssignmentScreenContext,
  ) => ReactNode;
  projectPanel?: (projectId: number, year: number) => ReactNode;
  selectionActions?: (
    rows: AssignmentItem[],
    refresh: () => Promise<unknown>,
    context: AssignmentScreenContext,
  ) => ReactNode;
  refreshVersion?: number;
}
const warningOptions = [
  ["OVER_MM", "MM 1.0 초과"],
  ["PRE_CONTRACT", "미계약 선투입"],
  ["ON_LEAVE", "휴직 기간 배정"],
  ["DISPATCH_NONREG", "파견에 프리랜서·외주 배정"],
];
export default function UI12Screen({
  name = "투입 배정",
  groupName = "프로젝트·투입",
  onDirtyChange,
  initialProjectId,
  initialAssignmentId,
  toolbar,
  dialogs,
  detailActions,
  detailPanels,
  projectPanel,
  selectionActions,
  refreshVersion = 0,
}: AssignmentsScreenProps) {
  const location = useLocation(),
    navigate = useNavigate();
  const handoff = location.state as {
    assignmentProjectId?: number;
    assignmentId?: number;
  } | null;
  const currentYear = Number(seoulMonth().slice(0, 4));
  const [filters, setFilters] = useState<AssignmentFilters>(() => {
    const year = Number(sessionStorage.getItem("UI-12.year"));
    return assignmentDefaults(year >= 1 && year <= 9999 ? year : currentYear);
  });
  const [draft, setDraft] = useState(filters),
    [creating, setCreating] = useState(
      !!(initialProjectId || handoff?.assignmentProjectId),
    );
  const [selected, setSelected] = useState<number | null>(
    initialAssignmentId ?? handoff?.assignmentId ?? null,
  );
  const [checked, setChecked] = useState<number[]>([]),
    [message, setMessage] = useState(""),
    [highlight, setHighlight] = useState<number | null>(null),
    [tab, setTab] = useState("list");
  const mobile = useDeveloperReadOnly(),
    readonly = mobile || filters.year < currentYear;
  const list = useAdminRead(
    useCallback(() => listAssignments(filters), [filters, refreshVersion]),
  );
  const projects = useAdminRead(listProjectOptions),
    jobs = useCodes("JOB"),
    kinds = useCodes("ASSIGN_KIND"),
    chances = useCodes("EXT_POSSIBILITY");
  const [detailVersion, setDetailVersion] = useState(0),
    [detailDirty, setDetailDirty] = useState(false);
  const [leave, setLeave] = useState<(() => void) | null>(null);
  const requestLeave = (action: () => void) => {
    if (detailDirty) setLeave(() => action);
    else action();
  };
  const reportDetailDirty = useCallback(
    (dirty: boolean) => {
      setDetailDirty(dirty);
      onDirtyChange?.(dirty);
    },
    [onDirtyChange],
  );
  const refresh = async () => {
    await list.reload();
    setDetailVersion((v) => v + 1);
  };
  const context: AssignmentScreenContext = {
    filters,
    readOnly: readonly,
    refresh,
    openAssignment: (id) => requestLeave(() => setSelected(id)),
    onDirtyChange: reportDetailDirty,
  };
  useEffect(() => {
    if (location.pathname !== "/UI-12") return;
    if (handoff?.assignmentProjectId) {
      setCreating(true);
      setSelected(null);
    }
    if (handoff?.assignmentId) {
      setSelected(handoff.assignmentId);
      setCreating(false);
    }
  }, [location.key]);
  useEffect(() => {
    setChecked([]);
  }, [filters]);
  useEffect(() => {
    if (readonly) setCreating(false);
  }, [readonly]);
  const label = (codes: typeof jobs, code: string | null) =>
    codes.codes.find((c) => c.code === code)?.name ?? code ?? "미정";
  const year = (value: number) => {
    if (value < 1 || value > 9999) return;
    sessionStorage.setItem("UI-12.year", String(value));
    setFilters((f) => ({ ...f, year: value, page: 0 }));
    setDraft((d) => ({ ...d, year: value }));
  };
  const sort = (column: string) =>
    setFilters((f) => ({
      ...f,
      page: 0,
      sort: `${column},${f.sort === `${column},asc` ? "desc" : "asc"}`,
    }));
  const reset = () => {
    const f = assignmentDefaults(filters.year);
    setFilters(f);
    setDraft(f);
  };
  const projectNavigate = (id: number) =>
    navigate(`/UI-01?open=${encodeURIComponent(id)}`);
  const columns = [
    ["asgn_no", "투입ID"],
    ["prj_nm", "프로젝트"],
    ["dev_nm", "이름"],
    ["job_cd", "직무"],
    ["bgn_dt", "투입일"],
    ["end_dt", "철수일"],
    ["bill_untprc_amt", "월 단가"],
    ["current_month_mm_qty", "월 MM"],
    ["year_mm_qty", `${filters.year}년 MM`],
    ["ext_psblty_cd", "연장 가능성"],
    ["asgn_se_cd", "투입 구분"],
  ];
  return (
    <section data-testid="assignments-screen" data-screen-id="SCR-UI-12">
      <ScreenHeading
        screenId="UI-12"
        name={name}
        groupName={groupName}
        actions={
          !readonly && (
            <>
              {toolbar}
              <Can perm="UI-12.create">
                <Button
                  variant="primary"
                  data-testid="assignment-add"
                  onClick={() => setCreating(true)}
                >
                  + 배정
                </Button>
              </Can>
            </>
          )
        }
      />
      <div className={styles.period}>
        <Button
          data-testid="assignment-year-prev"
          onClick={() => year(filters.year - 1)}
        >
          이전 해
        </Button>
        <span>
          {filters.year}년 {readonly && <Badge tone="locked">읽기 전용</Badge>}
        </span>
        <Button
          data-testid="assignment-year-next"
          onClick={() => year(filters.year + 1)}
        >
          다음 해
        </Button>
      </div>
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      <form
        data-testid="assignment-search-form"
        className={styles.search}
        onSubmit={(e) => {
          e.preventDefault();
          setFilters({
            ...draft,
            sort: filters.sort,
            size: filters.size,
            page: 0,
          });
        }}
      >
        <label>
          프로젝트
          <select
            data-testid="assignment-filter-project"
            value={draft.projectId ?? ""}
            onChange={(e) =>
              setDraft((d) => ({
                ...d,
                projectId: e.target.value ? Number(e.target.value) : undefined,
              }))
            }
          >
            <option value="">전체</option>
            {(projects.data ?? []).map((p) => (
              <option
                data-testid={`assignment-project-option-${p.id}`}
                key={p.id}
                value={p.id}
              >
                {p.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          직무
          <select
            data-testid="assignment-filter-job"
            value={draft.job}
            onChange={(e) => setDraft((d) => ({ ...d, job: e.target.value }))}
          >
            <option value="">전체</option>
            {jobs.codes.map((c) => (
              <option
                data-testid={`assignment-job-option-${c.code.toLowerCase().replaceAll("_", "-")}`}
                key={c.code}
                value={c.code}
              >
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          경고
          <select
            data-testid="assignment-filter-warning"
            value={draft.warning}
            onChange={(e) =>
              setDraft((d) => ({ ...d, warning: e.target.value }))
            }
          >
            <option value="">전체</option>
            {warningOptions.map(([v, n]) => (
              <option
                data-testid={`assignment-warning-option-${v.toLowerCase().replaceAll("_", "-")}`}
                key={v}
                value={v}
              >
                {n}
              </option>
            ))}
          </select>
        </label>
        <label>
          이름
          <input
            data-testid="assignment-filter-name"
            placeholder="이름 또는 관리번호"
            value={draft.q}
            onChange={(e) => setDraft((d) => ({ ...d, q: e.target.value }))}
          />
        </label>
        <Button data-testid="assignment-reset" type="button" onClick={reset}>
          초기화
        </Button>
        <Button data-testid="assignment-search" type="submit" variant="primary">
          조회
        </Button>
      </form>
      {[list, projects, jobs, kinds, chances].map((s, i) => (
        <ErrorBanner key={i} error={s.error} />
      ))}
      {filters.projectId && projectPanel && (
        <div role="tablist">
          <Button
            role="tab"
            aria-selected={tab === "list"}
            data-testid="assignment-list-tab"
            onClick={() => setTab("list")}
          >
            투입 목록
          </Button>
          <Button
            role="tab"
            aria-selected={tab === "months"}
            data-testid="assignment-project-months-tab"
            onClick={() => setTab("months")}
          >
            월별 MM
          </Button>
        </div>
      )}
      {tab === "months" && filters.projectId && projectPanel ? (
        projectPanel(filters.projectId, filters.year)
      ) : (
        <>
          <div className={styles.toolbar}>
            <span>총 {list.data?.total ?? 0}건</span>
            {checked.length > 0 && <span>{checked.length}건 선택</span>}
            {selectionActions?.(
              (list.data?.items ?? []).filter((r) =>
                checked.includes(r.asgn_id),
              ),
              refresh,
              context,
            )}
          </div>
          {list.loading ? (
            <Skeleton />
          ) : (
            !list.error && (
              <div className={styles.tableScroll}>
                <table className={styles.table}>
                  <thead>
                    <tr>
                      {!readonly && <th>선택</th>}
                      <th>경고</th>
                      {columns.map(([key, text]) => (
                        <th
                          data-testid={`assignment-header-${key.replaceAll("_", "-")}`}
                          key={key}
                        >
                          <Button
                            small
                            variant="text"
                            data-testid={`assignment-sort-${key.replaceAll("_", "-")}`}
                            onClick={() => sort(key)}
                          >
                            {text}
                          </Button>
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {(list.data?.items ?? []).map((r) => (
                      <tr
                        key={r.asgn_id}
                        data-testid={`assignment-row-${r.asgn_id}`}
                        className={
                          highlight === r.asgn_id ? styles.highlight : ""
                        }
                      >
                        {!readonly && (
                          <td>
                            <input
                              type="checkbox"
                              data-testid={`assignment-check-${r.asgn_id}`}
                              aria-label={`${r.asgn_no} 선택`}
                              checked={checked.includes(r.asgn_id)}
                              onChange={(e) =>
                                setChecked((v) =>
                                  e.target.checked
                                    ? [...v, r.asgn_id]
                                    : v.filter((id) => id !== r.asgn_id),
                                )
                              }
                            />
                          </td>
                        )}
                        <td
                          title={r.warnings.map((w) => w.message).join(" · ")}
                        >
                          {r.warnings.length ? (
                            <Badge tone="warning">
                              경고 {r.warnings.length}
                            </Badge>
                          ) : (
                            "–"
                          )}
                        </td>
                        <td>
                          <Button
                            variant="text"
                            data-testid={`assignment-open-${r.asgn_id}`}
                            onClick={() => setSelected(r.asgn_id)}
                          >
                            {r.asgn_no}
                          </Button>
                        </td>
                        <td>
                          <Button
                            variant="text"
                            data-testid={`assignment-project-${r.asgn_id}`}
                            onClick={() => projectNavigate(r.prj_id)}
                          >
                            {r.prj_nm}
                          </Button>
                        </td>
                        <td>
                          <Button
                            variant="text"
                            data-testid={`assignment-person-${r.asgn_id}`}
                            onClick={() => setSelected(r.asgn_id)}
                          >
                            {r.dev_nm}
                          </Button>
                        </td>
                        <td>{label(jobs, r.job_cd)}</td>
                        <td>{r.bgn_dt}</td>
                        <td>{r.end_dt}</td>
                        <td className={styles.number}>
                          {r.bill_untprc_amt?.toLocaleString("ko-KR") ?? "–"}
                        </td>
                        <td
                          title={`그 사람 그 달 합계 ${r.person_month_mm_qty.toFixed(2)}`}
                        >
                          {r.current_month_mm_qty.toFixed(2)}
                        </td>
                        <td>{r.year_mm_qty.toFixed(2)}</td>
                        <td>
                          <Badge
                            tone={
                              r.ext_psblty_cd === "H"
                                ? "success"
                                : r.ext_psblty_cd === "M"
                                  ? "warning"
                                  : r.ext_psblty_cd === "L"
                                    ? "danger"
                                    : "neutral"
                            }
                          >
                            {label(chances, r.ext_psblty_cd)}
                          </Badge>
                        </td>
                        <td>
                          <Badge>{label(kinds, r.asgn_se_cd)}</Badge>
                        </td>
                      </tr>
                    ))}
                    {list.data?.items.length === 0 && (
                      <tr>
                        <td
                          colSpan={readonly ? 12 : 13}
                          className={styles.empty}
                        >
                          <div className={styles.emptyContent}>
                            <span>조건에 맞는 결과가 없습니다</span>
                            <Button
                              data-testid="assignment-empty-reset"
                              onClick={reset}
                            >
                              조건 초기화
                            </Button>
                          </div>
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            )
          )}
          <Pagination
            page={filters.page + 1}
            size={filters.size}
            total={list.data?.total ?? 0}
            onPage={(page) => setFilters((f) => ({ ...f, page: page - 1 }))}
            onSize={(size) => setFilters((f) => ({ ...f, size, page: 0 }))}
          />
        </>
      )}
      {creating && !readonly && (
        <UI12M1
          projectId={
            initialProjectId ??
            handoff?.assignmentProjectId ??
            filters.projectId
          }
          reload={list.reload}
          onDirtyChange={onDirtyChange}
          onCancel={() => setCreating(false)}
          onSaved={(s) => {
            setCreating(false);
            setHighlight(s.assignment.asgn_id);
            setMessage("배정을 저장했습니다.");
          }}
        />
      )}
      {selected != null && (
        <UI12DetailPanel
          id={selected}
          refreshVersion={`${refreshVersion}-${detailVersion}`}
          onClose={() => requestLeave(() => setSelected(null))}
          onProject={(id) => requestLeave(() => projectNavigate(id))}
          onDeveloper={(id) =>
            requestLeave(() => navigate(`/UI-02?open=${id}`))
          }
          actions={
            readonly ? undefined : (row) => detailActions?.(row, context)
          }
        >
          {detailPanels ? (row) => detailPanels(row, context) : undefined}
        </UI12DetailPanel>
      )}
      {dialogs}
      {leave && (
        <ConfirmDialog
          title="저장하지 않은 내용이 있습니다."
          cancelLabel="계속 작성"
          confirmLabel="나가기"
          onCancel={() => setLeave(null)}
          onConfirm={() => {
            reportDetailDirty(false);
            leave();
            setLeave(null);
          }}
        >
          나가면 입력한 내용이 사라집니다.
        </ConfirmDialog>
      )}
    </section>
  );
}
