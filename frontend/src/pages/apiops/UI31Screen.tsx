import { useCallback, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { Button, Badge, Pagination, Skeleton } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { failure } from "../admin/client";
import { ScreenHeading } from "../shared/ScreenHeading";
import { useCodes } from "../shared/useCodes";
import { listSalesPartOptions } from "../salespartsops/client";
import {
  boardDefaults,
  getProjectBoard,
  getProgressParameters,
  projectProgressPath,
  type BoardFilters,
  type ProgressParameter,
} from "./boardClient";
import {
  ProjectBoardSummary,
  ProjectTrafficLight,
  ProjectProgressBars,
  ProjectWarningStrip,
} from "./ProjectProgressWidgets";
import styles from "./ProjectBoard.module.css";

const columns = [
  ["light", "신호등"],
  ["prj_nm", "프로젝트"],
  ["pmName", "PM"],
  ["currentPhase", "현재 단계"],
  ["actl_prgs_rt", "진척률 (계획 / 실적)"],
  ["cost_use_rt", "원가 사용률"],
  ["open_issue_count", "열린 이슈"],
  ["overdue_issue_count", "기한 지난 이슈"],
  ["last_memo_dt", "마지막 업데이트"],
];
export interface ProjectBoardScreenProps {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
  issuesPanel?: ReactNode;
  issueOpenCount?: number;
  onDownload?: (
    filters: Pick<BoardFilters, "light" | "type" | "part">,
  ) => Promise<unknown>;
  refreshVersion?: number;
}
// SCR-UI-31: period NONE. Other feature owners supply issues and workbook actions through these slots.
export default function UI31Screen({
  name = "프로젝트 현황판",
  groupName = "프로젝트·투입",
  issuesPanel,
  issueOpenCount,
  onDownload,
  refreshVersion = 0,
}: ProjectBoardScreenProps) {
  const navigate = useNavigate();
  const [filters, setFilters] = useState(boardDefaults),
    [draft, setDraft] = useState(boardDefaults);
  const [tab, setTab] = useState("board");
  const board = useAdminRead(
    useCallback(() => getProjectBoard(filters), [filters, refreshVersion]),
  );
  const params = useAdminRead(getProgressParameters);
  const parts = useAdminRead(listSalesPartOptions);
  const types = useCodes("CONTRACT_TYPE");
  const [downloadError, setDownloadError] = useState<ReturnType<
    typeof failure
  > | null>(null);
  const [downloading, setDownloading] = useState(false);
  const download = async () => {
    if (!onDownload) return;
    setDownloading(true);
    setDownloadError(null);
    try {
      await onDownload({
        light: filters.light,
        type: filters.type,
        part: filters.part,
      });
    } catch (e) {
      setDownloadError(failure(e));
    } finally {
      setDownloading(false);
    }
  };
  const change = (key: "light" | "type" | "part", value: string) => {
    setDraft((d) => ({ ...d, [key]: value }));
    setFilters((f) => ({ ...f, [key]: value, page: 0 }));
  };
  const reset = () => {
    const next = boardDefaults();
    setFilters(next);
    setDraft(next);
  };
  return (
    <section data-screen-id="SCR-UI-31" data-testid="project-board-screen">
      <ScreenHeading
        screenId="UI-31"
        name={name}
        groupName={groupName}
        actions={
          onDownload && (
            <Can perm="UI-31.download">
              <Button
                data-testid="board-download"
                pending={downloading}
                onClick={() => void download()}
              >
                엑셀 다운로드
              </Button>
            </Can>
          )
        }
      />
      <ErrorBanner
        error={board.error}
        onRetry={() => void board.reload().catch(() => {})}
      />
      <ErrorBanner error={downloadError} onRetry={() => void download()} />
      {board.data && <ProjectBoardSummary summary={board.data.summary} />}
      <div
        className={styles.tabs}
        role="tablist"
        aria-label="프로젝트 현황판 보기"
      >
        <button
          role="tab"
          aria-selected={tab === "board"}
          data-testid="board-tab"
          onClick={() => setTab("board")}
        >
          현황판
        </button>
        {issuesPanel != null && (
          <button
            role="tab"
            aria-selected={tab === "issues"}
            data-testid="board-issues-tab"
            onClick={() => setTab("issues")}
          >
            이슈 모아 보기{issueOpenCount != null ? ` (${issueOpenCount})` : ""}
          </button>
        )}
      </div>
      {issuesPanel != null && (
        <div
          role="tabpanel"
          data-testid="board-issues-panel"
          hidden={tab !== "issues"}
        >
          {issuesPanel}
        </div>
      )}
      <div role="tabpanel" data-testid="board-panel" hidden={tab !== "board"}>
        {!!board.data?.stale_projects.length && (
          <ProjectWarningStrip>
            2주 넘게 상태를 적지 않은 프로젝트:{" "}
            {board.data.stale_projects.map((p) => (
              <button
                key={p.prj_id}
                className={styles.textButton}
                data-testid={`board-stale-${p.prj_id}`}
                onClick={() => navigate(projectProgressPath(p.prj_id))}
              >
                {p.prj_nm} ({p.memo_elapsed_days}일)
              </button>
            ))}
          </ProjectWarningStrip>
        )}
        <form
          data-testid="board-search-form"
          className={styles.search}
          onSubmit={(e) => {
            e.preventDefault();
            setFilters({
              ...draft,
              page: 0,
              size: filters.size,
              sort: filters.sort,
            });
          }}
        >
          <label>
            신호등
            <select
              aria-label="신호등"
              data-testid="board-light-filter"
              value={draft.light}
              onChange={(e) => change("light", e.target.value)}
            >
              <option value="">전체</option>
              {["위험", "주의", "정상"].map((v) => (
                <option
                  data-testid={`board-light-option-${["위험", "주의", "정상"].indexOf(v)}`}
                  key={v}
                >
                  {v}
                </option>
              ))}
            </select>
          </label>
          <label>
            계약유형
            <select
              aria-label="계약유형"
              data-testid="board-type-filter"
              disabled={types.loading || !!types.error}
              value={draft.type}
              onChange={(e) => change("type", e.target.value)}
            >
              <option value="">전체</option>
              {types.codes.map((c) => (
                <option
                  data-testid={`board-type-option-${c.code.toLowerCase().replaceAll("_", "-")}`}
                  key={c.code}
                  value={c.code}
                >
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            영업 파트
            <select
              aria-label="영업 파트"
              data-testid="board-part-filter"
              disabled={parts.loading || !!parts.error}
              value={draft.part}
              onChange={(e) => change("part", e.target.value)}
            >
              <option value="">전체</option>
              {parts.data?.map((p) => (
                <option
                  data-testid={`board-part-option-${p.id}`}
                  key={p.id}
                  value={p.id}
                >
                  {p.hq ? "본사(직접 영업)" : p.name}
                </option>
              ))}
            </select>
          </label>
          <Button type="button" data-testid="board-reset" onClick={reset}>
            초기화
          </Button>
          <Button type="submit" variant="primary" data-testid="board-query">
            조회
          </Button>
        </form>
        <ErrorBanner error={types.error} onRetry={types.retry} />
        <ErrorBanner
          error={parts.error}
          onRetry={() => void parts.reload().catch(() => {})}
        />
        <div className={styles.toolbar}>
          <span>총 {board.data?.total.toLocaleString("ko-KR") ?? "–"}건</span>
        </div>
        {board.loading ? (
          <Skeleton label="현황판 불러오는 중" />
        ) : (
          !board.error &&
          board.data && (
            <>
              <div className={styles.tableScroll}>
                <table className={styles.table}>
                  <thead>
                    <tr>
                      {columns.map(([key, label]) => (
                        <th
                          data-testid={`board-column-${key.toLowerCase().replaceAll("_", "-")}`}
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
                            data-testid={`board-sort-${key.toLowerCase().replaceAll("_", "-")}`}
                            onClick={() =>
                              setFilters((f) => ({
                                ...f,
                                page: 0,
                                sort: `${key},${f.sort === `${key},asc` ? "desc" : "asc"}`,
                              }))
                            }
                          >
                            {label}
                          </button>
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {board.data.items.map((r) => (
                      <tr
                        key={r.prj_id}
                        data-testid={`board-row-${r.prj_id}`}
                        onClick={() => navigate(projectProgressPath(r.prj_id))}
                      >
                        <td>
                          <ProjectTrafficLight
                            light={r.light}
                            reasons={r.reasons}
                          />
                          <small className={styles.reasons}>
                            {r.reasons.join(" · ")}
                          </small>
                        </td>
                        <td>
                          <button
                            className={styles.textButton}
                            data-testid={`board-open-${r.prj_id}`}
                            onClick={(e) => {
                              e.stopPropagation();
                              navigate(projectProgressPath(r.prj_id));
                            }}
                          >
                            {r.prj_nm}
                          </button>
                          <small>
                            {r.custName ?? "–"} ·{" "}
                            {types.codes.find((c) => c.code === r.cntrct_ty_cd)
                              ?.name ?? r.cntrct_ty_cd}
                          </small>
                        </td>
                        <td>{r.pmName}</td>
                        <td>
                          {r.currentPhase ? (
                            <>
                              {r.currentPhase.phs_seq}. {r.currentPhase.phs_nm}
                              <small>
                                <Badge
                                  tone={
                                    r.currentPhase.status === "지연"
                                      ? "danger"
                                      : r.currentPhase.status === "진행 중"
                                        ? "info"
                                        : "neutral"
                                  }
                                >
                                  {r.currentPhase.status}
                                </Badge>
                              </small>
                            </>
                          ) : (
                            "단계 표가 없습니다."
                          )}
                        </td>
                        <td>
                          <ProjectProgressBars
                            plan={r.plan_prgs_rt}
                            actual={r.actl_prgs_rt}
                          />
                        </td>
                        <td>
                          {r.cost_use_rt == null ? (
                            "–"
                          ) : (
                            <span
                              tabIndex={0}
                              title={`누적 원가 ${r.cumulative_cost_amt?.toLocaleString("ko-KR")}원 / 예산 ${r.budget_amt?.toLocaleString("ko-KR")}원`}
                            >
                              {r.cost_use_rt.toFixed(1)}% ⓘ
                              <small>
                                진척 대비 {r.cost_progress_diff_rt?.toFixed(1)}
                                %p
                              </small>
                            </span>
                          )}
                        </td>
                        <td>{r.open_issue_count}</td>
                        <td>{r.overdue_issue_count}</td>
                        <td>
                          {r.last_memo_dt ?? "메모없음"}
                          <small>
                            {r.memo_elapsed_days == null ? (
                              "–"
                            ) : (
                              <Badge
                                tone={
                                  r.memo_elapsed_days > 14
                                    ? "warning"
                                    : "neutral"
                                }
                              >
                                {r.memo_elapsed_days}일 전
                              </Badge>
                            )}
                          </small>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {board.data.items.length === 0 && (
                  <div className={styles.empty}>
                    <p>조건에 맞는 결과가 없습니다.</p>
                    <Button data-testid="board-empty-reset" onClick={reset}>
                      조건 초기화
                    </Button>
                  </div>
                )}
              </div>
              <Pagination
                page={filters.page + 1}
                size={filters.size}
                total={board.data.total}
                onPage={(page) => setFilters((f) => ({ ...f, page: page - 1 }))}
                onSize={(size) => setFilters((f) => ({ ...f, size, page: 0 }))}
              />
            </>
          )
        )}
        <ErrorBanner
          error={params.error}
          onRetry={() => void params.reload().catch(() => {})}
        />
        {params.loading ? (
          <Skeleton label="기준값 불러오는 중" />
        ) : (
          !params.error &&
          params.data && <ProjectBoardExplanation parameters={params.data} />
        )}
      </div>
    </section>
  );
}

export function ProjectBoardExplanation({
  parameters,
}: {
  parameters: ProgressParameter[];
}) {
  const value = (key: string) => {
    const p = parameters.find((p) => p.oprt_std_id === key);
    return p?.num_val ?? p?.txt_val;
  };
  return (
    <aside
      data-testid="board-explanation"
      className={styles.explanation}
      aria-label="진척률·신호등 계산 설명"
    >
      <h3>진척률·신호등 계산 설명</h3>
      <p>
        계획 진척률 = 종료한 단계 비중 + 진행 중 단계 비중 × 지난 날 ÷ 단계 날
        수. 예정 시작일 당일은 0이며 소수 첫째 자리로 반올림합니다.
      </p>
      <p>
        실적 진척률 = 턴키·내부는 가장 최근 주간 메모, 월단위·파견은 계획
        진척률과 같습니다.
      </p>
      <p>
        원가 사용률 = 누적 원가 ÷ 예산 × 100. 예산 = 현재 계약금액 ×{" "}
        {value("BUDGET_RATE")}%. 진척 대비 원가 = 원가 사용률 − 실적 진척률.
        턴키만 표시하며 예산이 없거나 0이면 숨깁니다.
      </p>
      <p>
        신호등: 일정이 계획보다 {value("SIG_DELAY_WARN")}%p 이상 늦으면 주의,{" "}
        {value("SIG_DELAY_RISK")}%p 이상 늦으면 위험. 진척 대비 원가{" "}
        {value("SIG_COST_WARN")}%p 이상은 주의, {value("SIG_COST_RISK")}%p
        이상은 위험입니다.
      </p>
      <p>
        기한 지난 높은 이슈는 위험, 다른 기한 지난 이슈 또는{" "}
        {value("SIG_STALE_DAYS")}일 초과 업데이트 없음은 주의입니다. 겹치면 더
        나쁜 등급을 표시합니다. 단계 표가 없으면 일정·원가 조건은 제외합니다.
      </p>
      <p>
        업데이트 없음 요약·안내는 기준값과 별개로 항상 14일 초과입니다. 메모와
        수행 시작일이 모두 없으면 경과일 판단에서 제외합니다.
      </p>
    </aside>
  );
}
