import { useCallback } from "react";
import { Badge, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { getCostAllocation } from "./client";
import styles from "../developers/Developers.module.css";

export function AllocationPanel({
  month,
  projectId,
}: {
  month: string;
  projectId?: number;
}) {
  const state = useAdminRead(
    useCallback(() => getCostAllocation(month, projectId), [month, projectId]),
  );
  const amount = (value: number | null) =>
    value == null ? "–" : value.toLocaleString("ko-KR");
  return (
    <section data-testid="cost-allocation-panel" aria-label="월별 배분 근거">
      <h3>월별 배분 근거</h3>
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
            <p>
              {state.data.calculation_month} · {state.data.unit}{" "}
              <Badge tone={state.data.closed ? "locked" : "info"}>
                {state.data.closed ? "마감 스냅샷" : "원천 재계산"}
              </Badge>
            </p>
            {state.data.warnings.map((warning, index) => (
              <p
                role="status"
                key={`${index}-${warning}`}
                data-testid={`allocation-warning-${index}`}
              >
                {warning}
              </p>
            ))}
            <p>미배분 원가: {amount(state.data.unallocated_cost_amt)}</p>
            <div className={styles.tableScroll}>
              <table className={styles.table} aria-label="월별 배분 결과">
                <thead>
                  <tr>
                    <th>개발자 ID</th>
                    <th>투입 ID</th>
                    <th>프로젝트 ID</th>
                    <th>분류</th>
                    <th>MM</th>
                    <th>총 MM</th>
                    <th>배분 인건비</th>
                    <th>직접경비</th>
                    <th>개인 참고매출</th>
                  </tr>
                </thead>
                <tbody>
                  {state.data.items.length === 0 && (
                    <tr>
                      <td colSpan={9} className={styles.empty}>
                        조건에 맞는 결과가 없습니다
                      </td>
                    </tr>
                  )}
                  {state.data.items.map((row, index) => (
                    <tr
                      key={`${row.dev_id}-${row.asgn_id}-${row.prj_id}-${row.aloc_ty_cd}-${index}`}
                      data-testid={`allocation-row-${index}`}
                    >
                      <td>{row.dev_id ?? "–"}</td>
                      <td>{row.asgn_id ?? "–"}</td>
                      <td>{row.prj_id ?? "–"}</td>
                      <td>{row.aloc_ty_cd}</td>
                      <td>{row.mm_qty?.toFixed(2) ?? "–"}</td>
                      <td>{row.tot_mm_qty?.toFixed(2) ?? "–"}</td>
                      <td className={styles.amount}>
                        {amount(row.lbr_cst_amt)}
                      </td>
                      <td className={styles.amount}>
                        {amount(row.drct_expns_amt)}
                      </td>
                      <td className={styles.amount}>{amount(row.sls_amt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )
      )}
    </section>
  );
}
