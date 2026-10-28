import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { Button, Modal, Skeleton, LockedBanner } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { can } from "../../shared/types";
import { failure } from "../admin/client";
import { useAdminRead } from "../admin/useAdminResource";
import { getProject } from "../projects/client";
import {
  listProjectOptions,
  listProjectAssignments,
} from "../projectsops/client";
import { getMonthClose, monthStatus } from "../shared/api";
import { seoulMonth } from "../shared/PeriodBar";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import { ApiFailure } from "../../shared/api";
import * as client from "./client";
import table from "../developers/Developers.module.css";
import styles from "./DispatchAttendance.module.css";

export interface DispatchAttendancePanelProps {
  month?: string;
  projectId?: number;
  onChanged?: () => Promise<unknown>;
  upload?: ReactNode;
}
const fields = [
  ["work_day_qty", "근무일수"],
  ["rglr_tm_qty", "정규 근무시간"],
  ["ovtm_tm_qty", "초과 근무시간"],
  ["mth_work_day_qty", "해당 월 근무일수"],
] as const;

export function DispatchAttendancePanel({
  month: initialMonth,
  projectId: initialProject,
  onChanged,
  upload,
}: DispatchAttendancePanelProps) {
  const [month, setMonth] = useState(initialMonth ?? seoulMonth());
  const [projectId, setProjectId] = useState(initialProject ?? 0);
  const [scope, setScope] = useState({ month, projectId });
  const optionsRead = useCallback(() => listProjectOptions(), []);
  const options = useAdminRead(optionsRead);
  const read = useCallback(async () => {
    if (!scope.projectId) return null;
    const [attendance, project, assignments, close] = await Promise.all([
      client.getDispatchAttendance(scope.month, scope.projectId),
      getProject(scope.projectId),
      listProjectAssignments(scope.projectId),
      getMonthClose(scope.month, scope.month),
    ]);
    return {
      attendance,
      project,
      assignments,
      closed: monthStatus(close, scope.month) === "CLOSED",
    };
  }, [scope]);
  const state = useAdminRead(read);
  const [rows, setRows] = useState<client.DispatchAttendanceRow[]>([]);
  const [dirty, setDirty] = useState<number[]>([]);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null);
  const [message, setMessage] = useState("");
  const busy = useRef(false);
  const mobile = useDeveloperReadOnly();
  const past =
    Number(scope.month.slice(0, 4)) < Number(seoulMonth().slice(0, 4));
  const readonly = mobile || past || state.data?.closed || !can("UI-13.update");
  useEffect(() => {
    setRows(state.data?.attendance.items ?? []);
    setDirty([]);
  }, [state.data]);
  const search = () => {
    if (
      pending ||
      (dirty.length > 0 &&
        !window.confirm("저장하지 않은 근태를 버리고 조회할까요?"))
    )
      return;
    setError(null);
    setMessage("");
    setScope({ month, projectId });
  };
  const save = async () => {
    if (busy.current || readonly || !state.data || state.loading || state.error)
      return;
    setError(null);
    setMessage("");
    const items = rows.filter((row) => dirty.includes(row.dev_id));
    const method = state.data.project.stlm_mthd_cd;
    const days = new Date(
      Number(scope.month.slice(0, 4)),
      Number(scope.month.slice(5)),
      0,
    ).getDate();
    for (const row of items) {
      for (const [key, label] of fields) {
        const required =
          key === "mth_work_day_qty" ||
          (method === "DAY" && key === "work_day_qty") ||
          (method === "HOUR" && key === "rglr_tm_qty");
        const value = row[key];
        if (
          (required && value === null) ||
          (value !== null && (!Number.isFinite(value) || value < 0)) ||
          (key === "mth_work_day_qty" &&
            value !== null &&
            (value <= 0 || value > days))
        ) {
          setError(
            new ApiFailure(
              `${label} 입력값을 확인하세요.`,
              400,
              null,
              key,
              "VALIDATION_ERROR",
            ),
          );
          return;
        }
      }
    }
    busy.current = true;
    setPending(true);
    try {
      await client.saveDispatchAttendance(
        scope.month,
        scope.projectId,
        items.map((row) => ({
          asgn_id: row.asgn_id,
          dev_id: row.dev_id,
          work_day_qty: row.work_day_qty,
          rglr_tm_qty: row.rglr_tm_qty,
          ovtm_tm_qty: row.ovtm_tm_qty,
          mth_work_day_qty: row.mth_work_day_qty,
        })),
      );
      await state.reload();
      await onChanged?.();
      setMessage("근태를 저장했습니다. ⑦ 투입 MM은 변경하지 않았습니다.");
    } catch (e) {
      setError(failure(e));
    } finally {
      busy.current = false;
      setPending(false);
    }
  };
  return (
    <section data-testid="dispatch-attendance-panel" className={styles.panel}>
      <div className={table.search}>
        <label>
          월{" "}
          <input
            data-testid="attendance-month"
            type="month"
            value={month}
            disabled={pending}
            onChange={(e) => setMonth(e.target.value)}
          />
        </label>
        <label>
          프로젝트{" "}
          <select
            data-testid="attendance-project"
            value={projectId}
            disabled={pending}
            onChange={(e) => setProjectId(Number(e.target.value))}
          >
            <option value={0}>선택</option>
            {options.data
              ?.filter((p) => p.type === "DP")
              .map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
          </select>
        </label>
        <Button
          data-testid="attendance-search"
          variant="primary"
          disabled={!month || !projectId || pending}
          onClick={search}
        >
          조회
        </Button>
      </div>
      <ErrorBanner error={options.error} />
      <ErrorBanner error={state.error} />
      <ErrorBanner error={error} />
      {error?.field && <p role="alert">{error.field}: 입력값을 확인하세요.</p>}
      {message && (
        <p role="status" data-testid="attendance-success">
          {message}
        </p>
      )}
      {readonly && (
        <LockedBanner>
          {past
            ? "과거 연도는 조회만 가능합니다."
            : state.data?.closed
              ? "마감된 달은 수정할 수 없습니다."
              : "조회 전용입니다."}
        </LockedBanner>
      )}
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error && (
          <>
            <div className={table.tableScroll}>
              <table className={table.table}>
                <thead>
                  <tr>
                    <th>인력</th>
                    {fields.map(([key, label]) => (
                      <th key={key}>{label}</th>
                    ))}
                    <th>산출 MM</th>
                    <th>⑦ 투입 MM</th>
                    <th>단가·근태 경고</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.length === 0 && (
                    <tr>
                      <td colSpan={8}>
                        {scope.projectId
                          ? "조회 결과가 없습니다."
                          : "월과 프로젝트를 선택해 주세요."}
                      </td>
                    </tr>
                  )}
                  {rows.map((row, index) => (
                    <tr
                      key={row.asgn_id}
                      data-testid={`attendance-row-${row.asgn_id}`}
                    >
                      <td>
                        {state.data?.assignments.find(
                          (a) => a.asgn_id === row.asgn_id,
                        )?.name ?? `인력 ${row.dev_id}`}
                        <small> · 투입 {row.asgn_id}</small>
                      </td>
                      {fields.map(([key, label]) => (
                        <td key={key}>
                          <input
                            type="number"
                            min={0}
                            step="0.1"
                            aria-label={`${label} ${row.asgn_id}`}
                            data-testid={`attendance-${key.replaceAll("_", "-")}-${row.asgn_id}`}
                            value={row[key] ?? ""}
                            disabled={
                              Boolean(readonly) ||
                              pending ||
                              month !== scope.month ||
                              projectId !== scope.projectId
                            }
                            aria-invalid={
                              error?.field === key ||
                              error?.field === `items[${index}].${key}`
                            }
                            onChange={(e) => {
                              const value =
                                e.target.value === ""
                                  ? null
                                  : Number(e.target.value);
                              setRows((current) =>
                                current.map((r) =>
                                  r.asgn_id === row.asgn_id
                                    ? { ...r, [key]: value }
                                    : r,
                                ),
                              );
                              setDirty((current) =>
                                current.includes(row.dev_id)
                                  ? current
                                  : [...current, row.dev_id],
                              );
                            }}
                          />
                        </td>
                      ))}
                      <td>
                        {row.work_day_qty !== null &&
                        row.mth_work_day_qty &&
                        row.mth_work_day_qty > 0
                          ? (
                              Math.round(
                                (row.work_day_qty / row.mth_work_day_qty +
                                  Number.EPSILON) *
                                  100,
                              ) / 100
                            ).toFixed(2)
                          : "–"}
                      </td>
                      <td>{row.assignment_mm?.toFixed(2) ?? "–"}</td>
                      <td>
                        {!row.price_available && (
                          <span className={styles.warning}>단가 미입력</span>
                        )}
                        {row.warnings.map((warning) => (
                          <div key={warning} className={styles.warning}>
                            {warning}
                          </div>
                        ))}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {!readonly && (
              <Can perm="UI-13.update">
                <div className={table.writeActions}>
                  <Button
                    data-testid="attendance-save"
                    variant="primary"
                    disabled={
                      pending ||
                      !dirty.length ||
                      !state.data ||
                      !!state.error ||
                      month !== scope.month ||
                      projectId !== scope.projectId
                    }
                    onClick={() => void save()}
                  >
                    {pending ? "저장 중…" : "저장"}
                  </Button>
                </div>
              </Can>
            )}
            {!readonly && upload}
          </>
        )
      )}
    </section>
  );
}

export function DispatchAttendanceDialog({
  onClose,
  ...props
}: DispatchAttendancePanelProps & { onClose: () => void }) {
  return (
    <Modal title="파견 근태" onCancel={onClose}>
      <div data-testid="dispatch-attendance-dialog">
        <DispatchAttendancePanel {...props} />
        <Button data-testid="attendance-close" onClick={onClose}>
          닫기
        </Button>
      </div>
    </Modal>
  );
}
