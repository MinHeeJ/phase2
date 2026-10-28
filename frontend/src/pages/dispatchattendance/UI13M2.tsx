import { useCallback, useEffect, useState, type ReactNode } from "react";
import {
  Badge,
  Button,
  LockedBanner,
  Modal,
  Skeleton,
} from "../../shared/Controls";
import { TextInput, SelectInput } from "../../shared/Inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";

import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import {
  getProject,
  listProjectAssignments,
  listProjectOptions,
} from "../projects/client";
import { getMonthClose, monthStatus } from "../shared/api";
import { seoulMonth } from "../shared/PeriodBar";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import {
  getDispatchAttendance,
  saveDispatchAttendance,
  type DispatchAttendanceInput,
} from "./client";
import table from "../developers/Developers.module.css";
import styles from "./DispatchAttendance.module.css";

type Quantity =
  | "work_day_qty"
  | "rglr_tm_qty"
  | "ovtm_tm_qty"
  | "mth_work_day_qty";
type Draft = Record<Quantity, string>;
const columns: { key: Quantity; label: string }[] = [
  { key: "work_day_qty", label: "근무일수" },
  { key: "rglr_tm_qty", label: "정규 근무시간" },
  { key: "ovtm_tm_qty", label: "초과 근무시간" },
  { key: "mth_work_day_qty", label: "해당 월 근무일수" },
];
export interface UI13M2Props {
  projectId?: number;
  month?: string;
  readOnly?: boolean;
  onClose: () => void;
  onSaved: () => void | Promise<unknown>;
  uploadRegion?: (scope: {
    projectId: number;
    month: string;
    onApplied: () => Promise<unknown>;
  }) => ReactNode;
}
export function UI13M2({
  projectId,
  month: initialMonth,
  readOnly = false,
  onClose,
  onSaved,
  uploadRegion,
}: UI13M2Props) {
  const [month, setMonth] = useState(initialMonth ?? seoulMonth());
  const [project, setProject] = useState(projectId ? String(projectId) : "");
  const [scope, setScope] = useState<{
    projectId: number;
    month: string;
  } | null>(
    projectId ? { projectId, month: initialMonth ?? seoulMonth() } : null,
  );
  const [drafts, setDrafts] = useState<Record<number, Draft>>({});
  const [leaving, setLeaving] = useState(false);
  const [invalid, setInvalid] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const mobile = useDeveloperReadOnly();
  const projects = useAdminRead(useCallback(listProjectOptions, []));
  const query = useAdminRead(
    useCallback(async () => {
      if (!scope) return null;
      const [attendance, detail, people, months] = await Promise.all([
        getDispatchAttendance(scope.month, scope.projectId),
        getProject(scope.projectId),
        listProjectAssignments(scope.projectId),
        getMonthClose(scope.month, scope.month),
      ]);
      return {
        attendance,
        detail,
        people,
        closed: monthStatus(months, scope.month) === "CLOSED",
      };
    }, [scope]),
  );
  const reload = async () => {
    await query.reload();
    await onSaved();
  };
  const write = useAdminWrite(reload);
  const past = (scope?.month ?? month).slice(0, 4) < seoulMonth().slice(0, 4);
  const restricted = readOnly || mobile || past;
  const locked = restricted || !!query.data?.closed;
  const dirty = Object.keys(drafts).length > 0;
  useEffect(() => {
    const guard = (event: BeforeUnloadEvent) => {
      if (dirty) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", guard);
    return () => window.removeEventListener("beforeunload", guard);
  }, [dirty]);
  const close = () => {
    if (write.pending) return;
    if (dirty) setLeaving(true);
    else onClose();
  };
  const search = () => {
    if (dirty) {
      setInvalid("먼저 저장하거나 닫기에서 변경 내용을 버려 주세요.");
      return;
    }
    if (!month || !project) {
      setInvalid("월과 프로젝트를 선택하세요.");
      return;
    }
    setInvalid(null);
    setSaved(false);
    write.clear();
    setScope({ month, projectId: Number(project) });
  };
  const rows = query.data?.attendance.items ?? [];
  const value = (id: number, key: Quantity) => {
    const row = rows.find((r) => r.asgn_id === id)!;
    return drafts[id]?.[key] ?? (row[key] == null ? "" : String(row[key]));
  };
  const change = (id: number, key: Quantity, next: string) => {
    const row = rows.find((r) => r.asgn_id === id)!;
    setDrafts((previous) => ({
      ...previous,
      [id]: {
        ...(Object.fromEntries(
          columns.map((c) => [
            c.key,
            row[c.key] == null ? "" : String(row[c.key]),
          ]),
        ) as Draft),
        ...previous[id],
        [key]: next,
      },
    }));
    setInvalid(null);
    setSaved(false);
    write.clear();
  };
  const save = () => {
    if (!scope || locked || query.loading || query.error || !query.data) return;
    const method = query.data.detail.stlm_mthd_cd;
    const items: DispatchAttendanceInput[] = [];
    for (const row of rows.filter((r) => drafts[r.asgn_id])) {
      const values = drafts[row.asgn_id];
      const item: DispatchAttendanceInput = {
        asgn_id: row.asgn_id,
        dev_id: row.dev_id,
        mth_work_day_qty: Number(values.mth_work_day_qty),
      };
      for (const column of columns) {
        const raw = values[column.key];
        const required =
          column.key === "mth_work_day_qty" ||
          (method === "DAY" && column.key === "work_day_qty") ||
          (method === "HOUR" && column.key === "rglr_tm_qty");
        const maxDays = new Date(`${scope.month}-01T12:00:00Z`);
        maxDays.setUTCMonth(maxDays.getUTCMonth() + 1, 0);
        if (
          (required && !raw) ||
          (raw &&
            (!/^\d+(\.\d)?$/.test(raw) || !Number.isFinite(Number(raw)))) ||
          (column.key === "mth_work_day_qty" &&
            (Number(raw) <= 0 || Number(raw) > maxDays.getUTCDate()))
        ) {
          setInvalid(`${row.dev_id} · ${column.label} 입력 범위를 확인하세요.`);
          return;
        }
        if (raw !== "") item[column.key] = Number(raw);
      }
      items.push(item);
    }
    void write.save(
      () => saveDispatchAttendance(scope.month, scope.projectId, items),
      () => {
        setDrafts({});
        setSaved(true);
      },
    );
  };
  return (
    <Modal title="파견 근태" onCancel={close}>
      <section
        data-screen-id="SCR-UI-13-M2"
        data-testid="dispatch-attendance-dialog"
        className={styles.content}
      >
        <div className={table.search}>
          <TextInput
            label="월"
            name="attendance-month"
            type="month"
            required
            value={month}
            disabled={write.pending}
            onValueChange={setMonth}
          />
          <SelectInput
            label="프로젝트"
            name="attendance-project"
            required
            value={project}
            disabled={projects.loading || write.pending}
            onValueChange={setProject}
            options={(projects.data ?? [])
              .filter((p) => p.type === "DP")
              .map((p) => ({
                value: String(p.id),
                label: `${p.no} · ${p.name}`,
              }))}
          />
          <Button
            data-testid="attendance-search"
            onClick={search}
            disabled={projects.loading || write.pending}
          >
            조회
          </Button>
        </div>
        <ErrorBanner
          error={projects.error}
          onRetry={() => void projects.reload().catch(() => {})}
        />
        <ErrorBanner
          error={query.error}
          onRetry={() => void query.reload().catch(() => {})}
        />
        <ErrorBanner
          error={write.error}
          pending={write.pending}
          onRetry={() => void write.recover()}
        />
        {write.error?.field && (
          <p role="alert" data-testid="attendance-field-error">
            {write.error.field}: {write.error.message}
          </p>
        )}
        {invalid && (
          <p role="alert" data-testid="attendance-validation">
            {invalid}
          </p>
        )}
        {saved && (
          <p role="status" data-testid="attendance-success">
            근태를 저장했습니다.
          </p>
        )}
        {restricted && (
          <LockedBanner>
            과거 연도·모바일·조회 전용에서는 근태를 조회만 할 수 있습니다.
          </LockedBanner>
        )}
        {query.data?.closed && (
          <LockedBanner>마감된 달은 변경할 수 없습니다.</LockedBanner>
        )}
        {!scope && <p>월과 DP DAY/HOUR 프로젝트를 선택한 뒤 조회하세요.</p>}
        {query.loading && scope && <Skeleton />}
        {query.data && !query.loading && !query.error && (
          <>
            <p className={table.help}>
              근태 MM은 근무일수 ÷ 해당 월 근무일수로 소수 둘째 자리
              반올림합니다. ⑦ 투입 MM·단가는 변경하지 않습니다.
            </p>
            {!rows.length ? (
              <p data-testid="attendance-empty">
                해당 월의 유효한 DAY/HOUR 투입이 없습니다.
              </p>
            ) : (
              <div className={table.tableScroll}>
                <table className={table.table}>
                  <thead>
                    <tr>
                      <th>인력</th>
                      {columns.map((c) => (
                        <th key={c.key}>
                          {c.label}
                          {c.key === "mth_work_day_qty" ||
                          (query.data?.detail.stlm_mthd_cd === "DAY" &&
                            c.key === "work_day_qty") ||
                          (query.data?.detail.stlm_mthd_cd === "HOUR" &&
                            c.key === "rglr_tm_qty")
                            ? " *"
                            : ""}
                        </th>
                      ))}
                      <th>산출 MM</th>
                      <th>⑦ 투입 MM</th>
                      <th>단가·근태 경고</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr
                        key={row.asgn_id}
                        data-testid={`attendance-row-${row.asgn_id}`}
                      >
                        <td>
                          {query.data!.people.find(
                            (p) => p.asgn_id === row.asgn_id,
                          )?.name ?? `인력 ${row.dev_id}`}
                        </td>
                        {columns.map((column) => (
                          <td key={column.key}>
                            <input
                              data-testid={`attendance-${row.asgn_id}-${column.key.replace(/_/g, "-")}`}
                              aria-label={`${row.dev_id} ${column.label}`}
                              type="text"
                              inputMode="decimal"
                              value={value(row.asgn_id, column.key)}
                              readOnly={locked}
                              disabled={write.pending || write.uncertain}
                              onChange={(event) =>
                                change(
                                  row.asgn_id,
                                  column.key,
                                  event.target.value,
                                )
                              }
                              className={styles.quantity}
                            />
                          </td>
                        ))}
                        <td className={table.amount}>
                          {row.calculated_mm == null
                            ? "—"
                            : row.calculated_mm.toFixed(2)}
                          {drafts[row.asgn_id] && " (저장 후 갱신)"}
                        </td>
                        <td className={table.amount}>
                          {row.assignment_mm == null
                            ? "—"
                            : row.assignment_mm.toFixed(2)}
                        </td>
                        <td>
                          <Badge
                            tone={row.price_available ? "success" : "warning"}
                          >
                            {row.price_available ? "단가 있음" : "단가 미입력"}
                          </Badge>
                          {row.warnings.map((message) => (
                            <p key={message} className={styles.warning}>
                              {message}
                            </p>
                          ))}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {!locked && (
              <Can perm="UI-13.update">
                <div className={table.actions}>
                  <Button
                    data-testid="attendance-save"
                    variant="primary"
                    pending={write.pending}
                    disabled={!dirty || write.uncertain || !!query.error}
                    onClick={save}
                  >
                    저장
                  </Button>
                </div>
              </Can>
            )}
            {!locked &&
              scope &&
              uploadRegion?.({
                ...scope,
                onApplied: async () => {
                  setDrafts({});
                  await reload();
                },
              })}
          </>
        )}
        <div className={table.actions}>
          <Button
            data-testid="attendance-close"
            disabled={write.pending}
            onClick={close}
          >
            닫기
          </Button>
        </div>
      </section>
      {leaving && (
        <Modal
          title="작성 중인 내용을 버릴까요?"
          confirmation
          onCancel={() => setLeaving(false)}
        >
          <section data-testid="attendance-discard-dialog">
            <p>저장하지 않은 근태가 있습니다.</p>
            <Button
              data-testid="attendance-keep"
              onClick={() => setLeaving(false)}
            >
              계속 작성
            </Button>
            <Button data-testid="attendance-discard" onClick={onClose}>
              나가기
            </Button>
          </section>
        </Modal>
      )}
    </Modal>
  );
}
