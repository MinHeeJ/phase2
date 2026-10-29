import { useCallback } from "react";
import { Link } from "react-router-dom";
import { Badge, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import {
  getCostProjectCauses,
  projectNumber,
  type CostProjectCauses,
} from "./costProjectClient";
import table from "../developers/Developers.module.css";

export function UI04CausesPanel({
  projectId,
  year,
}: {
  projectId: number;
  year: number;
}) {
  const read = useAdminRead(
    useCallback(
      () => getCostProjectCauses<CostProjectCauses>(projectId, year),
      [projectId, year],
    ),
  );
  return (
    <section data-testid="project-causes-panel" aria-label="원가 초과 원인">
      <ErrorBanner
        error={read.error}
        onRetry={() => void read.reload().catch(() => {})}
      />
      {read.loading ? (
        <Skeleton />
      ) : (
        !read.error &&
        read.data && (
          <>
            <p>
              세 원인은 설명 지표이며 프로젝트 총원가에 다시 더하지 않습니다.
            </p>
            {read.data.warnings.map((warning) => (
              <p key={warning}>
                <Badge tone="warning">{warning}</Badge>
              </p>
            ))}
            <p>
              진척 대비 초과 {projectNumber(read.data.progress_excess_amt)}원 ·
              설명 안 되는 나머지 {projectNumber(read.data.unexplained_amt)}원
            </p>
            <div className={table.tableScroll}>
              <table className={table.table}>
                <thead>
                  <tr>
                    {["원인", "금액", "산출 근거", "담당", "권장 조치"].map(
                      (label) => (
                        <th key={label}>{label}</th>
                      ),
                    )}
                  </tr>
                </thead>
                <tbody>
                  {read.data.items.map((cause) => (
                    <tr
                      key={cause.code}
                      data-testid={`project-cause-${cause.code.toLowerCase().replaceAll("_", "-")}`}
                    >
                      <td>
                        {cause.label}{" "}
                        {cause.code === read.data?.main_cause && (
                          <Badge tone="danger">주원인</Badge>
                        )}
                        {cause.amount === 0 && <Badge>보조 정보</Badge>}
                      </td>
                      <td className={table.amount}>
                        {projectNumber(cause.amount)}
                      </td>
                      <td>{cause.basis}</td>
                      <td>{cause.owner}</td>
                      <td>
                        {cause.available && cause.navigation ? (
                          <Link
                            data-testid={`project-cause-link-${cause.code.toLowerCase().replaceAll("_", "-")}`}
                            to={cause.navigation}
                          >
                            {cause.action}
                          </Link>
                        ) : (
                          <Badge tone="locked">단가 재협상 · 승인 대기</Badge>
                        )}
                      </td>
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
