import { useEffect, useState } from "react";
import { Badge, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { failure } from "../admin/client";
import {
  checkAssignments,
  type AssignmentPreview,
  type AssignmentProposal,
} from "./client";
import styles from "./AssignmentDialogs.module.css";

export function AssignmentPreviewPanel({
  draft,
  valid,
}: {
  draft: AssignmentProposal;
  valid: boolean;
}) {
  const [data, setData] = useState<AssignmentPreview | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ReturnType<typeof failure> | null>(null);
  const payload = JSON.stringify(draft);
  useEffect(() => {
    setData(null);
    setError(null);
    setPending(false);
    if (!valid) return;
    const controller = new AbortController();
    setPending(true);
    void checkAssignments(
      JSON.parse(payload) as AssignmentProposal,
      controller.signal,
    )
      .then((result) => {
        if (!controller.signal.aborted) setData(result);
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(failure(e));
      })
      .finally(() => {
        if (!controller.signal.aborted) setPending(false);
      });
    return () => controller.abort();
  }, [payload, valid]);
  return (
    <section
      data-testid="assignment-preview"
      data-screen-id="SCR-UI-12-M1"
      aria-label="투입 경고 미리보기"
    >
      {pending && <Skeleton />}
      <ErrorBanner error={error} />
      {data && (
        <>
          {data.warnings.length > 0 && (
            <div
              className={styles.warning}
              data-testid="assignment-preview-warnings"
            >
              {data.warnings.map((warning, index) => (
                <p
                  data-testid={`assignment-preview-warning-${index}`}
                  key={`${warning.code}-${warning.work_ym}-${index}`}
                >
                  <Badge tone="warning">{warning.message}</Badge>
                </p>
              ))}
              <p>경고는 저장을 막지 않습니다.</p>
            </div>
          )}
          {data.months.length === 0 ? (
            <p>생성할 미마감 월별 MM이 없습니다.</p>
          ) : (
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>월</th>
                  <th>자동 MM</th>
                  <th>사람 월 합계</th>
                </tr>
              </thead>
              <tbody>
                {data.months.map((month) => (
                  <tr
                    key={month.work_ym}
                    data-testid={`assignment-preview-${month.work_ym}`}
                  >
                    <td>{month.work_ym}</td>
                    <td>{month.auto_mm_qty.toFixed(2)}</td>
                    <td
                      className={
                        month.person_month_mm_qty > 1 ? styles.danger : ""
                      }
                    >
                      {month.person_month_mm_qty.toFixed(2)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </section>
  );
}
