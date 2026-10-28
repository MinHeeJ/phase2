import { useCallback, useState, type ReactNode } from "react";
import {
  DataTable,
  Pagination,
  Skeleton,
  Badge,
  type Column,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import styles from "./DeveloperLifecycle.module.css";
import {
  listDeveloperAssignments,
  listDeveloperMonthlyCosts,
  listDeveloperHistory,
  type DeveloperAssignment,
  type DeveloperCost,
  type DeveloperChange,
  type DeveloperPage,
} from "./client";

function DeveloperReadPanel<T>({
  label,
  read,
  columns,
  rowKey,
  empty,
}: {
  label: string;
  read: (page: number, size: number) => Promise<DeveloperPage<T>>;
  columns: Column<T>[];
  rowKey: (row: T) => string;
  empty: string;
}) {
  const [page, setPage] = useState(0),
    [size, setSize] = useState<20 | 50 | 100>(50);
  const state = useAdminRead(
    useCallback(() => read(page, size), [read, page, size]),
  );
  return (
    <section
      role="tabpanel"
      aria-label={label}
      data-testid={`developer-${label === "투입 이력" ? "assignments" : label === "월별 원가" ? "monthly-costs" : "history"}`}
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
        !state.error && (
          <>
            {state.data?.items.length ? (
              <DataTable
                rows={state.data.items}
                total={state.data.total}
                columns={columns}
                rowKey={rowKey}
              />
            ) : (
              <p>{empty}</p>
            )}
            {!!state.data?.total && (
              <Pagination
                page={page + 1}
                size={size}
                total={state.data.total}
                onPage={(n) => setPage(n - 1)}
                onSize={(n) => {
                  setPage(0);
                  setSize(n);
                }}
              />
            )}
          </>
        )
      )}
    </section>
  );
}
export function DeveloperAssignments({ id }: { id: number }) {
  const read = useCallback(
    (page: number, size: number) => listDeveloperAssignments(id, page, size),
    [id],
  );
  return (
    <div
      className={styles.assignmentHistory}
      data-screen-id="SCR-UI-02"
      data-testid="developer-assignment-history"
    >
      <DeveloperReadPanel<DeveloperAssignment>
        label="투입 이력"
        read={read}
        empty="투입 이력이 없습니다."
        rowKey={(r) => String(r.asgn_id)}
        columns={[
          { key: "prj_nm", label: "프로젝트", render: (r) => r.prj_nm },
          {
            key: "job_name",
            label: "직무",
            render: (r) => r.job_name ?? r.job_cd,
          },
          {
            key: "bgn_dt",
            label: "시작 ~ 철수",
            render: (r) => `${r.bgn_dt} ~ ${r.end_dt}`,
          },
          {
            key: "asgn_rt",
            label: "투입률",
            kind: "number",
            render: (r) => `${(r.asgn_rt * 100).toLocaleString("ko-KR")}%`,
          },
          {
            key: "asgn_stts_cd",
            label: "상태",
            render: (r) => (
              <span className={styles.assignmentStatus}>
                <Badge tone={r.asgn_stts_cd === "ACTIVE" ? "info" : "locked"}>
                  {r.asgn_stts_cd}
                </Badge>
              </span>
            ),
          },
        ]}
      />
    </div>
  );
}
export function DeveloperMonthlyCosts({ id }: { id: number }) {
  const read = useCallback(
    (page: number, size: number) => listDeveloperMonthlyCosts(id, page, size),
    [id],
  );
  const codes = useCodes("EMP_TYPE");
  const money = (
    key: keyof DeveloperCost,
    label: string,
  ): Column<DeveloperCost> => ({
    key,
    label,
    kind: "number",
    sensitive: true,
    render: (r) => Number(r[key]).toLocaleString("ko-KR"),
  });
  return (
    <DeveloperReadPanel<DeveloperCost>
      label="월별 원가"
      read={read}
      empty="급여 업로드로 만든 월별 원가가 없습니다."
      rowKey={(r) => r.cst_ym}
      columns={[
        { key: "cst_ym", label: "연월", render: (r) => r.cst_ym },
        {
          key: "emp_se_cd",
          label: "직원구분",
          render: (r) =>
            codes.codes.find((c) => c.code === r.emp_se_cd)?.name ??
            r.emp_se_cd,
        },
        money("slry_amt", "월 급여"),
        money("alwnc_amt", "수당"),
        money("sins_cmpny_amt", "4대보험(회사)"),
        money("rtrpay_acml_amt", "퇴직금 적립"),
        money("indv_lbr_cst_amt", "개인 월 인건비"),
        money("real_pay_amt", "실지급액"),
      ]}
    />
  );
}
export function DeveloperHistory({ id }: { id: number }) {
  const read = useCallback(
    (page: number, size: number) => listDeveloperHistory(id, page, size),
    [id],
  );
  const names: Record<string, string> = {
    use_yn: "사용 여부",
    dev_nm: "이름",
    work_stts_cd: "근무 상태",
    bgn_dt: "시작일",
    end_dt: "종료일",
  };
  const value = (v: string | null): ReactNode => v ?? "–";
  return (
    <DeveloperReadPanel<DeveloperChange>
      label="변경 이력"
      read={read}
      empty="아직 바꾼 기록이 없습니다."
      rowKey={(r) =>
        `${r.changed_at}-${r.table_name}-${r.record_id}-${r.item}-${r.change_type}`
      }
      columns={[
        {
          key: "changed_at",
          label: "일시",
          render: (r) => r.changed_at.replace("T", " "),
        },
        {
          key: "item",
          label: "항목",
          render: (r) =>
            r.item
              ? (names[r.item] ?? r.item)
              : r.table_name === "mst.dev"
                ? "개발자"
                : "근무 상태",
        },
        { key: "before", label: "바꾸기 전", render: (r) => value(r.before) },
        { key: "after", label: "바꾼 뒤", render: (r) => value(r.after) },
        { key: "actor", label: "처리자", render: (r) => value(r.actor) },
      ]}
    />
  );
}
