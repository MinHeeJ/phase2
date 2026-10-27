import { useCallback, useState, type ReactNode } from "react";
import { Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { getProjectAssignmentMonths } from "./client";
import styles from "./Months.module.css";

export function UI12ProjectMonthsPanel({
  projectId,
  year,
  list,
}: {
  projectId: number | null;
  year: number;
  list: ReactNode;
}) {
  if (projectId == null) return <>{list}</>;
  return (
    <ProjectMonthTabs
      key={`${projectId}-${year}`}
      projectId={projectId}
      year={year}
      list={list}
    />
  );
}

function ProjectMonthTabs({
  projectId,
  year,
  list,
}: {
  projectId: number;
  year: number;
  list: ReactNode;
}) {
  const [selected, setSelected] = useState("list");
  return (
    <section data-testid="project-months-panel">
      <div role="tablist" aria-label="프로젝트 투입" className={styles.tabs}>
        <button
          role="tab"
          type="button"
          data-testid="project-months-list-tab"
          aria-selected={selected === "list"}
          onClick={() => setSelected("list")}
        >
          투입 목록
        </button>
        <button
          role="tab"
          type="button"
          data-testid="project-months-months-tab"
          aria-selected={selected === "months"}
          onClick={() => setSelected("months")}
        >
          월별 MM
        </button>
      </div>
      {selected === "list" ? (
        <div role="tabpanel" data-testid="project-months-list-panel">
          {list}
        </div>
      ) : (
        <ProjectMonthTable projectId={projectId} year={year} />
      )}
    </section>
  );
}

function ProjectMonthTable({
  projectId,
  year,
}: {
  projectId: number;
  year: number;
}) {
  const read = useCallback(
    () => getProjectAssignmentMonths(projectId, year),
    [projectId, year],
  );
  const state = useAdminRead(read);
  const months = Array.from(
    { length: 12 },
    (_, index) => `${year}-${String(index + 1).padStart(2, "0")}`,
  );
  const text = (value: number | undefined) =>
    value === undefined ? "–" : value.toFixed(2);
  return (
    <div
      role="tabpanel"
      data-testid="project-months-table-panel"
      aria-label="프로젝트 월별 MM"
    >
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        state.data && (
          <>
            <p>읽기 전용</p>
            <div className={styles.tableScroll}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>사람(투입)</th>
                    {months.map((month, index) => (
                      <th
                        data-testid={`project-months-header-${month}`}
                        key={month}
                      >
                        {index + 1}월
                      </th>
                    ))}
                    <th>합계</th>
                  </tr>
                </thead>
                <tbody>
                  {!state.data.items.length && (
                    <tr>
                      <td colSpan={14} className={styles.empty}>
                        조건에 맞는 결과가 없습니다
                      </td>
                    </tr>
                  )}
                  {state.data.items.map((row) => (
                    <tr
                      key={row.asgn_id}
                      data-testid={`project-months-row-${row.asgn_id}`}
                    >
                      <td>{row.dev_nm}</td>
                      {months.map((month) => (
                        <td
                          data-testid={`project-months-cell-${row.asgn_id}-${month}`}
                          key={month}
                        >
                          {text(
                            row.months.find((m) => m.work_ym === month)?.mm_qty,
                          )}
                        </td>
                      ))}
                      <td>{text(row.total_mm_qty)}</td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr data-testid="project-months-total-row">
                    <th>월별 합계</th>
                    {months.map((month) => (
                      <td
                        data-testid={`project-months-total-${month}`}
                        key={month}
                      >
                        {text(
                          state.data?.month_totals.find(
                            (m) => m.work_ym === month,
                          )?.mm_qty,
                        )}
                      </td>
                    ))}
                    <td>–</td>
                  </tr>
                </tfoot>
              </table>
            </div>
          </>
        )
      )}
    </div>
  );
}
