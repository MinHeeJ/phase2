import { useCallback } from "react";
import { DataTable, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { listPartnerAssignments, listPartnerHistory } from "./client";

export function PartnerAssignments({ id }: { id: string }) {
  const state = useAdminRead(
    useCallback(() => listPartnerAssignments(id), [id]),
  );
  return (
    <section
      data-testid="partner-assignments"
      role="tabpanel"
      aria-label="투입 내역"
    >
      <ErrorBanner
        error={state.error}
        onRetry={() => {
          void state.reload().catch(() => {});
        }}
      />
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        (state.data?.length ? (
          <DataTable
            rows={state.data}
            total={state.data.length}
            rowKey={(row) =>
              `${row.projectName}-${row.developerName}-${row.jobCode}-${row.endDate}`
            }
            columns={[
              {
                key: "projectName",
                label: "프로젝트",
                render: (row) => row.projectName,
              },
              {
                key: "developerName",
                label: "이름",
                render: (row) => row.developerName,
              },
              {
                key: "affiliation",
                label: "소속",
                render: (row) => row.affiliation,
              },
              { key: "jobName", label: "직무", render: (row) => row.jobName },
              {
                key: "assignmentRate",
                label: "투입률",
                kind: "number",
                render: (row) =>
                  `${(row.assignmentRate * 100).toLocaleString("ko-KR")}%`,
              },
              { key: "endDate", label: "철수일", render: (row) => row.endDate },
            ]}
          />
        ) : (
          <p>지금 투입 중인 내역이 없습니다.</p>
        ))
      )}
    </section>
  );
}
export function PartnerHistory({ id }: { id: string }) {
  const state = useAdminRead(useCallback(() => listPartnerHistory(id), [id]));
  return (
    <section
      data-testid="partner-history"
      role="tabpanel"
      aria-label="변경 이력"
    >
      <ErrorBanner
        error={state.error}
        onRetry={() => {
          void state.reload().catch(() => {});
        }}
      />
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        (state.data?.length ? (
          <DataTable
            rows={state.data}
            total={state.data.length}
            rowKey={(row) => `${row.changedAt}-${row.column}-${row.changeType}`}
            columns={[
              {
                key: "changedAt",
                label: "일시",
                render: (row) => row.changedAt.replace("T", " "),
              },
              { key: "itemName", label: "항목", render: (row) => row.itemName },
              {
                key: "before",
                label: "바꾸기 전",
                render: (row) => row.before ?? "–",
              },
              {
                key: "after",
                label: "바꾼 뒤",
                render: (row) => row.after ?? "–",
              },
              {
                key: "actorName",
                label: "처리자",
                render: (row) => row.actorName,
              },
            ]}
          />
        ) : (
          <p>아직 바꾼 기록이 없습니다.</p>
        ))
      )}
    </section>
  );
}
