import { useCallback, useState, type ReactNode } from "react";
import { Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { getProjectAssignmentMonths } from "./assignmentMonthsClient";
import tableStyles from "./ProjectLifecycle.module.css";
import styles from "./ProjectAssignmentMonths.module.css";

// SCR-UI-12#project-months: embedded in the selected project's /UI-12 results.
export function ProjectAssignmentMonthsPanel({
  projectId,
  year,
}: {
  projectId: number;
  year: number;
}) {
  return (
    <ProjectAssignmentMonthsTable
      key={`${projectId}-${year}`}
      projectId={projectId}
      year={year}
    />
  );
}
function ProjectAssignmentMonthsTable({
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
  return (
    <section
      role="tabpanel"
      id="project-months-panel"
      aria-label="월별 MM"
      data-testid="project-months-panel"
      data-screen-id="SCR-UI-12#project-months"
    >
      <h3>{year}년 월별 MM</h3>
      <p className={styles.readonly}>읽기 전용</p>
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        state.data &&
        (state.data.items.length ? (
          <div className={tableStyles.tableScroll}>
            <table className={`${tableStyles.table} ${styles.matrix}`}>
              <caption className={styles.caption}>
                {year}년 프로젝트 연간 투입 MM
              </caption>
              <thead>
                <tr>
                  <th scope="col">사람(투입)</th>
                  {state.data.month_totals.map((cell) => (
                    <th
                      scope="col"
                      key={cell.work_ym}
                      data-testid={`project-months-column-${cell.work_ym}`}
                    >
                      {Number(cell.work_ym.slice(5))}월
                    </th>
                  ))}
                  <th scope="col">합계</th>
                </tr>
              </thead>
              <tbody>
                {state.data.items.map((row) => (
                  <tr
                    key={row.asgn_id}
                    data-testid={`project-months-row-${row.asgn_id}`}
                  >
                    <th scope="row">{row.dev_nm}</th>
                    {row.months.map((cell) => (
                      <td
                        key={cell.work_ym}
                        data-testid={`project-months-cell-${row.asgn_id}-${cell.work_ym}`}
                      >
                        {cell.mm_qty.toFixed(2)}
                      </td>
                    ))}
                    <td>{row.total_mm_qty.toFixed(2)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr data-testid="project-months-totals">
                  <th scope="row">월별 합계</th>
                  {state.data.month_totals.map((cell) => (
                    <td
                      key={cell.work_ym}
                      data-testid={`project-months-total-${cell.work_ym}`}
                    >
                      {cell.mm_qty.toFixed(2)}
                    </td>
                  ))}
                  <td>
                    {state.data.month_totals
                      .reduce((sum, cell) => sum + cell.mm_qty, 0)
                      .toFixed(2)}
                  </td>
                </tr>
              </tfoot>
            </table>
          </div>
        ) : (
          <p className={tableStyles.empty}>조회 결과가 없습니다.</p>
        ))
      )}
    </section>
  );
}

export function ProjectAssignmentMonthTabs({
  projectId,
  year,
  children,
}: {
  projectId: number | null;
  year: number;
  children: ReactNode;
}) {
  if (projectId == null) return <>{children}</>;
  return (
    <SelectedProjectMonthTabs key={projectId} projectId={projectId} year={year}>
      {children}
    </SelectedProjectMonthTabs>
  );
}
function SelectedProjectMonthTabs({
  projectId,
  year,
  children,
}: {
  projectId: number;
  year: number;
  children: ReactNode;
}) {
  const [tab, setTab] = useState<"list" | "months">("list");
  return (
    <section data-testid="project-assignment-month-tabs">
      <div
        role="tablist"
        aria-label="프로젝트 투입 조회"
        className={styles.tabs}
      >
        <button
          type="button"
          role="tab"
          aria-selected={tab === "list"}
          aria-controls="project-assignment-list-panel"
          data-testid="project-assignment-list-tab"
          onClick={() => setTab("list")}
        >
          투입 목록
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === "months"}
          aria-controls="project-months-panel"
          data-testid="project-assignment-months-tab"
          onClick={() => setTab("months")}
        >
          월별 MM
        </button>
      </div>
      {tab === "months" ? (
        <ProjectAssignmentMonthsPanel projectId={projectId} year={year} />
      ) : (
        <div
          role="tabpanel"
          id="project-assignment-list-panel"
          aria-label="투입 목록"
          data-testid="project-assignment-list-panel"
        >
          {children}
        </div>
      )}
    </section>
  );
}
