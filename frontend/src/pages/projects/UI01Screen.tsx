import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
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
import { failure } from "../admin/client";
import { useCodes } from "../shared/useCodes";
import { ScreenHeading } from "../shared/ScreenHeading";
import { seoulMonth } from "../shared/PeriodBar";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { listSalesPartOptions } from "../salespartsops/client";
import {
  ProjectDrawer,
  ProjectStatusBadge,
  type ProjectDetailTab,
} from "./ProjectDrawer";
import {
  getProject,
  listProjects,
  projectDefaults,
  exportProjects,
  type ProjectDetail,
} from "./client";
import UI33Screen from "./UI33Screen";
import styles from "./Projects.module.css";

export interface ProjectsScreenProps {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
  detailTabs?: ProjectDetailTab[];
  onUpload?: (form: "B.9") => void;
  dialogs?: ReactNode;
  onPartChange?: (row: ProjectDetail, refresh: () => Promise<unknown>) => void;
  detailActions?: (
    row: ProjectDetail,
    refresh: () => Promise<unknown>,
  ) => ReactNode;
  refreshVersion?: number;
}
// SCR-UI-01: list, numeric/canonical-number deep link and same-tab UI-33 registration.
export default function UI01Screen({
  name = "프로젝트",
  groupName = "프로젝트·투입",
  onDirtyChange,
  detailTabs,
  onUpload,
  dialogs,
  onPartChange,
  detailActions,
  refreshVersion = 0,
}: ProjectsScreenProps) {
  const location = useLocation(),
    navigate = useNavigate(),
    readOnly = useDeveloperReadOnly();
  const currentYear = Number(seoulMonth().slice(0, 4));
  const [filters, setFilters] = useState(() => {
    const stored = Number(sessionStorage.getItem("UI-01.year"));
    return projectDefaults(
      stored >= 1 && stored <= 9999 ? stored : currentYear,
    );
  });
  const [draft, setDraft] = useState(filters),
    [selected, setSelected] = useState<number | string | null>(null),
    [highlight, setHighlight] = useState<ProjectDetail | null>(null),
    [message, setMessage] = useState("");
  const [downloadError, setDownloadError] = useState<ReturnType<
      typeof failure
    > | null>(null),
    [downloading, setDownloading] = useState(false);
  const list = useAdminRead(
    useCallback(() => listProjects(filters), [filters, refreshVersion]),
  );
  const types = useCodes("CONTRACT_TYPE"),
    statuses = useCodes("PROJECT_STATUS"),
    parts = useAdminRead(listSalesPartOptions);
  const projectRoute = useRef(location.pathname);
  if (location.pathname === "/UI-01" || location.pathname === "/UI-33")
    projectRoute.current = location.pathname;
  const registration = projectRoute.current === "/UI-33";
  useEffect(() => {
    const open = new URLSearchParams(location.search).get("open");
    if (open) setSelected(open);
  }, [location.search]);
  const created = (location.state as { projectCreated?: number } | null)
    ?.projectCreated;
  useEffect(() => {
    if (!created || registration) return;
    let active = true;
    void getProject(created)
      .then((row) => {
        if (!active) return;
        setHighlight(row);
        setMessage("저장했습니다.");
        const year = Number(row.cntrct_bgn_dt.slice(0, 4));
        sessionStorage.setItem("UI-01.year", String(year));
        setFilters(projectDefaults(year));
        setDraft(projectDefaults(year));
      })
      .catch((e) => {
        if (active) setDownloadError(failure(e));
      });
    return () => {
      active = false;
    };
  }, [created, registration]);
  const refresh = useCallback(
    async (text: string) => {
      await list.reload();
      if (highlight) {
        const updated = await getProject(highlight.prj_id);
        setHighlight(updated.use_yn === "Y" ? updated : null);
      }
      setMessage(text);
    },
    [list.reload, highlight],
  );
  const reset = () => {
    const f = projectDefaults(filters.year);
    setDraft(f);
    setFilters(f);
    setHighlight(null);
  };
  const yearChange = (year: number) => {
    if (year < 1 || year > 9999) return;
    sessionStorage.setItem("UI-01.year", String(year));
    setDraft((d) => ({ ...d, year }));
    setFilters((f) => ({ ...f, year, page: 0 }));
    setHighlight(null);
  };
  const rows =
    highlight && filters.page === 0
      ? [
          highlight,
          ...(list.data?.items ?? []).filter(
            (r) => r.prj_id !== highlight.prj_id,
          ),
        ].slice(0, filters.size)
      : (list.data?.items ?? []);
  const columns = [
    ["prj_no", "프로젝트ID"],
    ["prj_nm", "프로젝트명"],
    ["custName", "고객사"],
    ["cntrct_ty_cd", "계약유형"],
    ["cntrct_bgn_dt", "계약 기간"],
    ["cur_cntrct_amt", "계약금액"],
    ["assignmentCount", "투입 인원"],
    ["partName", "영업 파트"],
    ["prj_stts_cd", "상태"],
  ];
  const download = async () => {
    setDownloading(true);
    setDownloadError(null);
    try {
      await exportProjects(filters);
    } catch (e) {
      setDownloadError(failure(e));
    } finally {
      setDownloading(false);
    }
  };
  return (
    <section data-testid="project-workspace" data-screen-id="SCR-UI-01">
      <div hidden={registration}>
        <ScreenHeading
          screenId="UI-01"
          name={name}
          groupName={groupName}
          actions={
            !readOnly &&
            filters.year >= currentYear && (
              <div className={styles.actions}>
                <Can perm="UI-01.upload">
                  <Button
                    data-testid="project-upload"
                    disabled={!onUpload}
                    onClick={() => onUpload?.("B.9")}
                  >
                    엑셀 업로드
                  </Button>
                </Can>
                <Can perm="UI-01.create">
                  <Button
                    data-testid="project-create"
                    variant="primary"
                    onClick={() => navigate("/UI-33")}
                  >
                    + 등록
                  </Button>
                </Can>
              </div>
            )
          }
        />
        <div className={styles.period} aria-label="조회 기간">
          <Button
            data-testid="project-previous-year"
            disabled={filters.year <= 1}
            onClick={() => yearChange(filters.year - 1)}
          >
            ‹ 이전 연도
          </Button>
          <label>
            연도{" "}
            <input
              data-testid="project-year"
              aria-label="조회 연도"
              type="number"
              value={filters.year}
              min="1"
              max="9999"
              onChange={(e) => yearChange(Number(e.target.value))}
            />
          </label>
          <Button
            data-testid="project-next-year"
            disabled={filters.year >= 9999}
            onClick={() => yearChange(filters.year + 1)}
          >
            다음 연도 ›
          </Button>
        </div>
        {!!list.data?.unassignedCount && (
          <div className={styles.warning}>
            <span>
              영업 파트가 없는 프로젝트가 {list.data.unassignedCount}건
              있습니다. 파트를 정해야 파트별 실적·권한·정산에 들어갑니다.
            </span>
            <Button
              data-testid="project-unassigned"
              onClick={() => {
                setDraft((d) => ({ ...d, part: "NONE" }));
                setFilters((f) => ({ ...f, part: "NONE", page: 0 }));
                setHighlight(null);
              }}
            >
              이 건만 보기
            </Button>
          </div>
        )}
        <form
          className={styles.search}
          onSubmit={(e) => {
            e.preventDefault();
            setFilters({
              ...draft,
              year: filters.year,
              page: 0,
              size: filters.size,
              sort: filters.sort,
            });
            setHighlight(null);
          }}
        >
          <label>
            계약유형
            <select
              data-testid="project-type-filter"
              aria-label="조회 계약유형"
              value={draft.type}
              onChange={(e) =>
                setDraft((d) => ({ ...d, type: e.target.value }))
              }
            >
              <option value="">전체</option>
              {types.codes.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            상태
            <select
              data-testid="project-status-filter"
              aria-label="조회 상태"
              value={draft.status}
              onChange={(e) =>
                setDraft((d) => ({ ...d, status: e.target.value }))
              }
            >
              <option value="">전체</option>
              {statuses.codes.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            영업 파트
            <select
              data-testid="project-part-filter"
              aria-label="조회 영업 파트"
              value={draft.part}
              onChange={(e) =>
                setDraft((d) => ({ ...d, part: e.target.value }))
              }
            >
              <option value="">전체</option>
              {parts.data?.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.hq ? "본사(직접 영업)" : p.name}
                </option>
              ))}
              <option value="NONE">미지정</option>
            </select>
          </label>
          <label>
            프로젝트명·고객사
            <input
              data-testid="project-query-input"
              aria-label="프로젝트명·고객사"
              value={draft.q}
              onChange={(e) => setDraft((d) => ({ ...d, q: e.target.value }))}
            />
          </label>
          <Button data-testid="project-reset" type="button" onClick={reset}>
            조건 초기화
          </Button>
          <Button data-testid="project-query" variant="primary" type="submit">
            조회
          </Button>
        </form>
        <ErrorBanner
          error={list.error}
          onRetry={() => void list.reload().catch(() => {})}
        />
        <ErrorBanner error={types.error} onRetry={types.retry} />
        <ErrorBanner error={statuses.error} onRetry={statuses.retry} />
        <ErrorBanner
          error={parts.error}
          onRetry={() => void parts.reload().catch(() => {})}
        />
        <ErrorBanner error={downloadError} onRetry={() => void download()} />
        <div className={styles.toolbar}>
          <span>총 {list.data?.total.toLocaleString("ko-KR") ?? 0}건</span>
          <Can perm="UI-01.download">
            <Button
              data-testid="project-export"
              pending={downloading}
              onClick={() => void download()}
            >
              엑셀 다운로드
            </Button>
          </Can>
        </div>
        {list.loading && !list.data ? (
          <Skeleton />
        ) : (
          <div className={styles.tableScroll}>
            <table className={styles.table}>
              <thead>
                <tr>
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
                        data-testid={`project-sort-${key.toLowerCase().replaceAll("_", "-")}`}
                        onClick={() => {
                          setFilters((f) => ({
                            ...f,
                            page: 0,
                            sort: `${key},${f.sort === `${key},asc` ? "desc" : "asc"}`,
                          }));
                          setHighlight(null);
                        }}
                      >
                        {label}
                      </button>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr
                    key={r.prj_id}
                    data-testid={`project-row-${r.prj_id}`}
                    className={
                      highlight?.prj_id === r.prj_id ? styles.highlight : ""
                    }
                    onClick={() => setSelected(r.prj_id)}
                  >
                    <td>{r.prj_no}</td>
                    <td>
                      <button
                        data-testid={`project-open-${r.prj_id}`}
                        className={styles.textButton}
                        onClick={() => setSelected(r.prj_id)}
                      >
                        {r.prj_nm}
                      </button>
                    </td>
                    <td>
                      {r.cntrct_ty_cd === "IN" ? "–" : (r.custName ?? "–")}
                    </td>
                    <td>
                      <Badge>
                        {types.codes.find((c) => c.code === r.cntrct_ty_cd)
                          ?.name ?? r.cntrct_ty_cd}
                      </Badge>
                    </td>
                    <td>
                      {r.cntrct_bgn_dt} ~ {r.cur_end_dt}
                    </td>
                    <td className={styles.amount}>
                      {r.cntrct_ty_cd === "IN"
                        ? "–"
                        : (r.cur_cntrct_amt?.toLocaleString("ko-KR") ?? "–")}
                    </td>
                    <td>{r.assignmentCount}명</td>
                    <td>
                      {r.cntrct_ty_cd === "IN"
                        ? "없음"
                        : (r.partName ?? <Badge tone="warning">미지정</Badge>)}
                    </td>
                    <td>
                      <ProjectStatusBadge
                        code={r.prj_stts_cd}
                        label={
                          statuses.codes.find((c) => c.code === r.prj_stts_cd)
                            ?.name ?? r.prj_stts_cd
                        }
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!list.loading && !list.error && rows.length === 0 && (
              <div className={styles.empty}>
                <p>조건에 맞는 결과가 없습니다.</p>
                <Button data-testid="project-empty-reset" onClick={reset}>
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
          onPage={(page) => {
            setFilters((f) => ({ ...f, page: page - 1 }));
            setHighlight(null);
          }}
          onSize={(size) => {
            setFilters((f) => ({ ...f, size, page: 0 }));
            setHighlight(null);
          }}
        />
        {selected != null && (
          <ProjectDrawer
            key={`${selected}-${new URLSearchParams(location.search).get("tab") ?? "basic"}`}
            id={selected}
            initialTab={
              ["progress", "issues"].includes(
                new URLSearchParams(location.search).get("tab") ?? "",
              )
                ? new URLSearchParams(location.search).get("tab")!
                : "basic"
            }
            onClose={() => {
              setSelected(null);
              if (location.search) navigate("/UI-01", { replace: true });
            }}
            onSaved={refresh}
            onDirtyChange={onDirtyChange}
            tabs={detailTabs}
            onPartChange={onPartChange}
            actions={detailActions}
          />
        )}
        {dialogs}
      </div>
      {registration && (
        <UI33Screen onDirtyChange={onDirtyChange} onSaved={setHighlight} />
      )}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
    </section>
  );
}
