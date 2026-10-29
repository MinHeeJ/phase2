import { useCallback, useEffect, useState, type ReactNode } from "react";
import {
  Badge,
  Button,
  LockedBanner,
  Modal,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import {
  getProject,
  listProjectAssignments,
  listProjectOptions,
} from "../projects/client";
import { getMonthClose, monthStatus } from "../shared/api";
import { seoulMonth } from "../shared/PeriodBar";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import table from "../developers/Developers.module.css";
import styles from "./DispatchAttendance.module.css";
import {
  getDispatchAttendance,
  saveDispatchAttendance,
  type DispatchAttendanceInput,
} from "./client";

export interface DispatchAttendanceDialogProps {
  year: number;
  initialMonth?: string;
  initialProjectId?: number;
  onClose: () => void;
  onSaved: () => Promise<unknown>;
  onDirtyChange?: (dirty: boolean) => void;
  renderUpload?: (scope: {
    month: string;
    projectId: number;
    onApplied: () => Promise<unknown>;
  }) => ReactNode;
}
export function DispatchAttendanceDialog(props: DispatchAttendanceDialogProps) {
  const today = seoulMonth();
  const [month, setMonth] = useState(
    props.initialMonth ?? `${props.year}-${today.slice(5)}`,
  );
  const [projectId, setProjectId] = useState(
    props.initialProjectId ? String(props.initialProjectId) : "",
  );
  const [scope, setScope] = useState<{
    month: string;
    projectId: number;
  } | null>(
    props.initialProjectId
      ? { month, projectId: props.initialProjectId }
      : null,
  );
  const options = useAdminRead(listProjectOptions);
  const [dirty, setDirty] = useState(false);
  const [leave, setLeave] = useState<(() => void) | null>(null);
  const mobile = useDeveloperReadOnly();
  const navigate = (action: () => void) => {
    if (dirty) setLeave(() => action);
    else action();
  };
  useEffect(() => {
    props.onDirtyChange?.(dirty);
  }, [dirty, props.onDirtyChange]);
  useEffect(() => () => props.onDirtyChange?.(false), [props.onDirtyChange]);
  const readonly =
    mobile ||
    Number(scope?.month.slice(0, 4) ?? props.year) < Number(today.slice(0, 4));
  return (
    <Modal title="파견 근태" onCancel={() => navigate(props.onClose)}>
      <section
        data-testid="dispatch-attendance-dialog"
        className={styles.panel}
      >
        <form
          className={table.search}
          onSubmit={(event) => {
            event.preventDefault();
            navigate(() => {
              setDirty(false);
              setScope({ month, projectId: Number(projectId) });
            });
          }}
        >
          <label>
            월{" "}
            <input
              data-testid="attendance-month"
              type="month"
              required
              min={`${props.year}-01`}
              max={`${props.year}-12`}
              value={month}
              onChange={(event) => setMonth(event.target.value)}
            />
          </label>
          <label>
            프로젝트{" "}
            <select
              data-testid="attendance-project"
              required
              value={projectId}
              onChange={(event) => setProjectId(event.target.value)}
            >
              <option value="">선택</option>
              {(options.data ?? [])
                .filter((project) => project.type === "DP")
                .map((project) => (
                  <option key={project.id} value={project.id}>
                    {project.name}
                  </option>
                ))}
            </select>
          </label>
          <Button
            data-testid="attendance-search"
            type="submit"
            disabled={options.loading}
          >
            조회
          </Button>
        </form>
        <ErrorBanner
          error={options.error}
          onRetry={() => void options.reload().catch(() => {})}
        />
        {scope ? (
          <AttendanceEditor
            key={`${scope.month}-${scope.projectId}`}
            scope={scope}
            readonly={readonly}
            onDirty={setDirty}
            onSaved={props.onSaved}
            renderUpload={props.renderUpload}
          />
        ) : (
          <p>월과 DAY/HOUR 파견 프로젝트를 선택한 뒤 조회하세요.</p>
        )}
        <div className={table.actions}>
          <Button
            data-testid="attendance-close"
            onClick={() => navigate(props.onClose)}
          >
            닫기
          </Button>
        </div>
        {leave && (
          <div
            role="alertdialog"
            aria-label="미저장 근태"
            data-testid="attendance-leave-confirm"
          >
            <p>저장하지 않은 근태가 있습니다. 나갈까요?</p>
            <Button
              data-testid="attendance-stay"
              onClick={() => setLeave(null)}
            >
              계속 작성
            </Button>
            <Button
              data-testid="attendance-leave"
              onClick={() => {
                setDirty(false);
                setLeave(null);
                leave();
              }}
            >
              나가기
            </Button>
          </div>
        )}
      </section>
    </Modal>
  );
}
const quantities = [
  ["work_day_qty", "근무일수"],
  ["rglr_tm_qty", "정규 근무시간"],
  ["ovtm_tm_qty", "초과 근무시간"],
  ["mth_work_day_qty", "해당 월 근무일수"],
] as const;
function AttendanceEditor({
  scope,
  readonly,
  onDirty,
  onSaved,
  renderUpload,
}: {
  scope: { month: string; projectId: number };
  readonly: boolean;
  onDirty: (dirty: boolean) => void;
  onSaved: () => Promise<unknown>;
  renderUpload?: DispatchAttendanceDialogProps["renderUpload"];
}) {
  const read = useCallback(async () => {
    const [attendance, project, people, months] = await Promise.all([
      getDispatchAttendance(scope.month, scope.projectId),
      getProject(scope.projectId),
      listProjectAssignments(scope.projectId),
      getMonthClose(scope.month, scope.month),
    ]);
    return {
      attendance,
      project,
      people,
      closed: monthStatus(months, scope.month) === "CLOSED",
    };
  }, [scope.month, scope.projectId]);
  const state = useAdminRead(read);
  const [draft, setDraft] = useState<DispatchAttendanceInput[] | null>(null);
  const [message, setMessage] = useState("");
  const refresh = async () => {
    await state.reload();
    await onSaved();
  };
  const write = useAdminWrite(refresh);
  const locked =
    readonly ||
    state.data?.closed ||
    ["CLOSED_MONTH", "ATTENDANCE_BILLED"].includes(write.error?.code ?? "");
  const rows = draft ?? state.data?.attendance.items ?? [];
  const edit = (
    index: number,
    field: (typeof quantities)[number][0],
    value: string,
  ) => {
    const next = rows.map((row, i) =>
      i === index
        ? { ...row, [field]: value === "" ? undefined : Number(value) }
        : row,
    );
    setDraft(next);
    onDirty(true);
    setMessage("");
  };
  return (
    <section data-testid="attendance-editor">
      {readonly && (
        <LockedBanner>과거 연도와 휴대폰에서는 조회만 가능합니다.</LockedBanner>
      )}
      {state.data?.closed && (
        <LockedBanner>마감된 달은 근태를 수정할 수 없습니다.</LockedBanner>
      )}
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      <ErrorBanner
        error={write.error}
        onRetry={write.uncertain ? () => void write.recover() : undefined}
      />
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      {state.loading ? (
        <Skeleton />
      ) : (
        state.data &&
        !state.error && (
          <>
            <p className={table.help}>
              정산방식: {state.data.project.stlm_mthd_cd} · 산출 MM은 청구
              상세용이며 ⑦ 투입 MM·단가 이력은 변경하지 않습니다.
            </p>
            <div className={table.tableScroll}>
              <table className={table.table}>
                <thead>
                  <tr>
                    <th>인력</th>
                    {quantities.map(([key, label]) => (
                      <th key={key}>{label}</th>
                    ))}
                    <th>산출 MM</th>
                    <th>⑦ 투입 MM</th>
                    <th>단가·근태 경고</th>
                  </tr>
                </thead>
                <tbody>
                  {!rows.length && (
                    <tr>
                      <td colSpan={8}>조건에 맞는 결과가 없습니다</td>
                    </tr>
                  )}
                  {rows.map((row, index) => {
                    const original = state.data!.attendance.items[index];
                    const name =
                      state.data!.people.find(
                        (person) => person.asgn_id === row.asgn_id,
                      )?.name ?? `인력 ${row.dev_id}`;
                    const mm =
                      row.work_day_qty != null &&
                      row.mth_work_day_qty != null &&
                      row.mth_work_day_qty > 0
                        ? (
                            Math.round(
                              (row.work_day_qty / row.mth_work_day_qty +
                                Number.EPSILON) *
                                100,
                            ) / 100
                          ).toFixed(2)
                        : "–";
                    return (
                      <tr
                        key={row.asgn_id}
                        data-testid={`attendance-row-${row.asgn_id}`}
                      >
                        <td>{name}</td>
                        {quantities.map(([field, label]) => {
                          const fieldName = `items[${index}].${field}`;
                          return (
                            <td key={field}>
                              <input
                                className={styles.quantity}
                                type="number"
                                min="0"
                                step="0.1"
                                data-testid={`attendance-${row.asgn_id}-${field.replaceAll("_", "-")}`}
                                aria-label={`${name} ${label}`}
                                aria-invalid={write.error?.field === fieldName}
                                readOnly={Boolean(locked)}
                                disabled={write.pending}
                                value={row[field] ?? ""}
                                onChange={(event) =>
                                  edit(index, field, event.target.value)
                                }
                              />
                              {write.error?.field === fieldName && (
                                <div className={styles.fieldError}>
                                  {write.error.message}
                                </div>
                              )}
                            </td>
                          );
                        })}
                        <td>{mm}</td>
                        <td>{original.assignment_mm?.toFixed(2) ?? "–"}</td>
                        <td>
                          {!original.price_available && (
                            <Badge tone="warning">단가 누락</Badge>
                          )}
                          {original.warnings.map((warning) => (
                            <p className={styles.warning} key={warning}>
                              {warning}
                            </p>
                          ))}
                          {row.work_day_qty == null && (
                            <p className={styles.warning}>
                              근무일수 미입력: 산출 MM 없음
                            </p>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            {!locked && (
              <Can perm="UI-13.update">
                <div className={table.writeActions}>
                  <Button
                    data-testid="attendance-save"
                    variant="primary"
                    pending={write.pending}
                    disabled={!draft || !rows.length || write.uncertain}
                    onClick={() =>
                      void write.save(
                        () =>
                          saveDispatchAttendance(
                            scope.month,
                            scope.projectId,
                            rows,
                          ),
                        () => {
                          setDraft(null);
                          onDirty(false);
                          setMessage("근태를 저장했습니다.");
                        },
                      )
                    }
                  >
                    저장
                  </Button>
                </div>
              </Can>
            )}
            {!locked && renderUpload && (
              <Can perm="UI-13.upload">
                {renderUpload({
                  ...scope,
                  onApplied: async () => {
                    setDraft(null);
                    onDirty(false);
                    await refresh();
                  },
                })}
              </Can>
            )}
          </>
        )
      )}
    </section>
  );
}
