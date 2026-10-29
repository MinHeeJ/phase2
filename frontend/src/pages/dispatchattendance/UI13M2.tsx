import { useCallback, useEffect, useState, type ReactNode } from "react";
import {
  Badge,
  Button,
  LockedBanner,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import {
  getProject,
  listProjectAssignments,
  listProjectOptions,
} from "../projects/client";
import { getMonthClose, monthStatus } from "../shared/api";
import { seoulMonth } from "../shared/PeriodBar";
import * as client from "./client";
import table from "../developers/Developers.module.css";
import styles from "./DispatchAttendance.module.css";

export interface UI13M2Props {
  month?: string;
  projectId?: number;
  readOnly?: boolean;
  onCancel: () => void;
  onSaved?: () => Promise<unknown>;
  renderUpload?: (scope: {
    month: string;
    projectId: number;
    readOnly: boolean;
    onApplied: () => Promise<unknown>;
  }) => ReactNode;
}
export function UI13M2({
  month: initialMonth,
  projectId: initialProject,
  readOnly = false,
  onCancel,
  onSaved,
  renderUpload,
}: UI13M2Props) {
  const [month, setMonth] = useState(initialMonth ?? seoulMonth());
  const [projectId, setProjectId] = useState(initialProject ?? 0);
  const [scope, setScope] = useState(
    initialProject
      ? { month: initialMonth ?? seoulMonth(), projectId: initialProject }
      : null,
  );
  const [revision, setRevision] = useState(0);
  const projects = useAdminRead(listProjectOptions);
  const [scopeError, setScopeError] = useState("");
  return (
    <div className={styles.scrim} data-testid="dispatch-attendance-root">
      <aside
        role="dialog"
        aria-modal="true"
        aria-label="파견 근태"
        className={styles.drawer}
        data-testid="dispatch-attendance-dialog"
      >
        <header className={styles.header}>
          <h2>파견 근태</h2>
          <Button data-testid="attendance-close" onClick={onCancel}>
            닫기
          </Button>
        </header>
        <ErrorBanner
          error={projects.error}
          onRetry={() => void projects.reload().catch(() => {})}
        />
        <form
          className={table.search}
          data-testid="attendance-search"
          onSubmit={(event) => {
            event.preventDefault();
            if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month) || !projectId) {
              setScopeError("월과 프로젝트를 선택하세요.");
              return;
            }
            setScopeError("");
            setScope({ month, projectId });
            setRevision((old) => old + 1);
          }}
        >
          <label>
            월{" "}
            <input
              data-testid="attendance-month"
              type="month"
              value={month}
              onChange={(e) => setMonth(e.target.value)}
            />
          </label>
          <label>
            프로젝트{" "}
            <select
              data-testid="attendance-project"
              value={projectId || ""}
              onChange={(e) => setProjectId(Number(e.target.value))}
            >
              <option value="">선택</option>
              {(projects.data ?? [])
                .filter((p) => p.type === "DP")
                .map((p) => (
                  <option
                    key={p.id}
                    value={p.id}
                    data-testid={`attendance-project-option-${p.id}`}
                  >
                    {p.no} · {p.name}
                  </option>
                ))}
            </select>
          </label>
          <Button
            data-testid="attendance-query"
            type="submit"
            variant="primary"
          >
            조회
          </Button>
        </form>
        {scopeError && <p role="alert">{scopeError}</p>}
        {!scope && <p>월과 DAY/HOUR 파견 프로젝트를 선택한 뒤 조회하세요.</p>}
        {scope && (
          <AttendanceEditor
            key={`${scope.month}-${scope.projectId}-${revision}`}
            {...scope}
            readOnly={readOnly}
            onSaved={onSaved}
            renderUpload={renderUpload}
          />
        )}
      </aside>
    </div>
  );
}

type Quantity =
  | "work_day_qty"
  | "rglr_tm_qty"
  | "ovtm_tm_qty"
  | "mth_work_day_qty";
const quantities: { key: Quantity; label: string }[] = [
  { key: "work_day_qty", label: "근무일수" },
  { key: "rglr_tm_qty", label: "정규 근무시간" },
  { key: "ovtm_tm_qty", label: "초과 근무시간" },
  { key: "mth_work_day_qty", label: "해당 월 근무일수" },
];
function AttendanceEditor({
  month,
  projectId,
  readOnly,
  onSaved,
  renderUpload,
}: {
  month: string;
  projectId: number;
  readOnly: boolean;
  onSaved?: UI13M2Props["onSaved"];
  renderUpload?: UI13M2Props["renderUpload"];
}) {
  const state = useAdminRead(
    useCallback(async () => {
      const [attendance, project, people, close] = await Promise.all([
        client.getDispatchAttendance(month, projectId),
        getProject(projectId),
        listProjectAssignments(projectId),
        getMonthClose(month, month),
      ]);
      return {
        attendance,
        project,
        people,
        closed: monthStatus(close, month) === "CLOSED",
      };
    }, [month, projectId]),
  );
  const mobile = useDeveloperReadOnly();
  const locked =
    readOnly ||
    mobile ||
    month.slice(0, 4) < seoulMonth().slice(0, 4) ||
    !!state.data?.closed;
  const [draft, setDraft] = useState<
    Record<number, Partial<Record<Quantity, string>>>
  >({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState("");
  const [submitted, setSubmitted] = useState<number[]>([]);
  useEffect(() => {
    setDraft({});
    setErrors({});
  }, [state.data]);
  const refresh = async () => {
    await state.reload();
    await onSaved?.();
  };
  const write = useAdminWrite(refresh);
  const value = (row: client.DispatchAttendanceRow, key: Quantity) =>
    draft[row.asgn_id]?.[key] ?? String(row[key] ?? "");
  const blockedRow = (row: client.DispatchAttendanceRow) =>
    row.warnings.some(
      (w) => w.includes("확정 청구") || w.includes("배정이 중복"),
    );
  const save = () => {
    if (locked || !state.data || write.pending || write.uncertain) return;
    const validation: Record<string, string> = {};
    const items: client.DispatchAttendanceInput[] = [];
    const rows = state.data.attendance.items;
    for (let index = 0; index < rows.length; index++) {
      const row = rows[index];
      if (!draft[row.asgn_id] || blockedRow(row)) continue;
      const item: client.DispatchAttendanceInput = {
        asgn_id: row.asgn_id,
        dev_id: row.dev_id,
        mth_work_day_qty: null,
      };
      for (const q of quantities) {
        const text = value(row, q.key);
        const required =
          q.key === "mth_work_day_qty" ||
          (q.key === "work_day_qty" &&
            state.data.project.stlm_mthd_cd === "DAY") ||
          (q.key === "rglr_tm_qty" &&
            state.data.project.stlm_mthd_cd === "HOUR");
        const max =
          q.key === "mth_work_day_qty"
            ? new Date(
                Number(month.slice(0, 4)),
                Number(month.slice(5)),
                0,
              ).getDate()
            : q.key === "work_day_qty"
              ? 9999.9
              : 99999.9;
        if (
          (required && text === "") ||
          (text !== "" &&
            (!/^\d+(\.\d)?$/.test(text) ||
              Number(text) > max ||
              (q.key === "mth_work_day_qty" && Number(text) <= 0)))
        ) {
          validation[`items[${index}].${q.key}`] =
            `${q.label}: 필수값과 범위를 확인하세요. 소수 한 자리까지 입력할 수 있습니다.`;
        }
        item[q.key] =
          text === "" ? (q.key === "ovtm_tm_qty" ? 0 : null) : Number(text);
      }
      items.push(item);
    }
    setErrors(validation);
    if (Object.keys(validation).length || !items.length) return;
    setSubmitted(items.map((item) => item.asgn_id));
    void write.save(
      () => client.saveDispatchAttendance(month, projectId, items),
      () => setMessage("근태를 저장했습니다."),
    );
  };
  return (
    <section data-testid="attendance-editor" aria-label="근태 수동 입력">
      {locked && (
        <LockedBanner>
          읽기 전용입니다. 과거 연도·마감 월·휴대폰에서는 저장할 수 없습니다.
        </LockedBanner>
      )}
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      <ErrorBanner
        error={write.error}
        pending={write.pending}
        onRetry={() => void write.recover()}
      />
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        state.data && (
          <>
            <p>
              {month} · {state.data.project.prj_nm} ·{" "}
              {state.data.project.stlm_mthd_cd} 정산
            </p>
            <p>
              산출 MM은 ⑦ 투입 MM과 비교만 하며 원본을 변경하지 않습니다.
              초과근무 배율은 자동 추정하지 않습니다.
            </p>
            {state.data.attendance.warnings.map((w, i) => (
              <p
                key={i}
                className={styles.warning}
                data-testid={`attendance-warning-${i}`}
              >
                {w}
              </p>
            ))}
            <div className={table.tableScroll}>
              <table className={table.table}>
                <thead>
                  <tr>
                    <th>인력</th>
                    {quantities.map((q) => (
                      <th
                        key={q.key}
                        data-testid={`attendance-column-${q.key.replaceAll("_", "-")}`}
                      >
                        {q.label}
                      </th>
                    ))}
                    <th>산출 MM</th>
                    <th>⑦ 투입 MM</th>
                    <th>단가·근태 경고</th>
                  </tr>
                </thead>
                <tbody>
                  {state.data.attendance.items.length === 0 && (
                    <tr>
                      <td colSpan={8}>조건에 맞는 결과가 없습니다</td>
                    </tr>
                  )}
                  {state.data.attendance.items.map((row, index) => (
                    <tr
                      key={row.asgn_id}
                      data-testid={`attendance-row-${row.asgn_id}`}
                    >
                      <td>
                        {state.data!.people.find(
                          (p) => p.asgn_id === row.asgn_id,
                        )?.name ?? `인력 ${row.dev_id}`}
                      </td>
                      {quantities.map((q) => {
                        const field = `items[${index}].${q.key}`;
                        const serverField = `items[${submitted.indexOf(row.asgn_id)}].${q.key}`;
                        const error =
                          errors[field] ??
                          (write.error?.field === serverField
                            ? write.error.message
                            : "");
                        return (
                          <td key={q.key}>
                            <input
                              className={styles.input}
                              inputMode="decimal"
                              data-testid={`attendance-${row.asgn_id}-${q.key.replaceAll("_", "-")}`}
                              aria-label={`${row.asgn_id} ${q.label}`}
                              value={value(row, q.key)}
                              readOnly={locked || blockedRow(row)}
                              disabled={write.pending || write.uncertain}
                              aria-invalid={!!error}
                              onChange={(e) => {
                                const text = e.target.value;
                                setDraft((old) => ({
                                  ...old,
                                  [row.asgn_id]: {
                                    ...old[row.asgn_id],
                                    [q.key]: text,
                                  },
                                }));
                                setErrors({});
                                setMessage("");
                                write.clear();
                              }}
                            />
                            {error && (
                              <small role="alert" className={styles.error}>
                                {error}
                              </small>
                            )}
                          </td>
                        );
                      })}
                      <td>{row.calculated_mm?.toFixed(2) ?? "—"}</td>
                      <td>{row.assignment_mm?.toFixed(2) ?? "—"}</td>
                      <td>
                        {!row.price_available && (
                          <Badge tone="warning">단가 미입력</Badge>
                        )}
                        {row.warnings.map((w, i) => (
                          <p className={styles.warning} key={i}>
                            {w}
                          </p>
                        ))}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {!locked &&
              state.data.attendance.items.some((r) => !blockedRow(r)) && (
                <Can perm="UI-13.update">
                  <div className={table.actions}>
                    <Button
                      data-testid="attendance-save"
                      variant="primary"
                      pending={write.pending}
                      disabled={
                        write.uncertain || Object.keys(draft).length === 0
                      }
                      onClick={save}
                    >
                      저장
                    </Button>
                  </div>
                </Can>
              )}
            {renderUpload?.({
              month,
              projectId,
              readOnly: locked,
              onApplied: refresh,
            })}
          </>
        )
      )}
    </section>
  );
}
