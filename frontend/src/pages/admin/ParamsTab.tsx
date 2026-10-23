import { useCallback, useId, useRef, useState } from "react";
import { readData } from "../../shared/api";
import type { PageResult } from "../../shared/types";
import {
  Button,
  DataTable,
  Pagination,
  Skeleton,
  type Column,
} from "../../shared/Controls";
import { TextInput } from "../../shared/Inputs";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { formatRatio, isMonth } from "../../lib/format";
import { AdminWorkDialog } from "./AdminDialogs";
import {
  adminPaths,
  listParamVersions,
  saveParamVersion,
  type OperatingParam,
} from "./client";
import { useAdminRead, useAdminWrite } from "./useAdminResource";
import { useSharedPreferences } from "../shared/preferences";
import styles from "./CommonCodes.module.css";

export function paramDisplay(row: OperatingParam) {
  return row.num_val === null
    ? `${row.txt_val ?? "–"}${row.unit_nm ?? ""}`
    : row.unit_nm === "%"
      ? formatRatio(row.num_val)
      : row.unit_nm === "%p"
        ? `${formatRatio(row.num_val).slice(0, -1)}%p`
        : `${row.num_val.toLocaleString("ko-KR", { maximumFractionDigits: 4 })}${row.unit_nm ?? ""}`;
}
export function validParamDraft(value: string, source: OperatingParam) {
  if (source.num_val === null)
    return value.trim().length > 0 && value.length <= 200;
  if (!/^\d+(?:\.\d{1,4})?$/.test(value)) return false;
  const [whole] = value.split(".");
  if (whole.replace(/^0+/, "").length > 14) return false;
  return !["%", "%p"].includes(source.unit_nm ?? "") || Number(value) <= 100;
}
export function ParamVersions({ row }: { row: OperatingParam }) {
  const read = useCallback(
      () => listParamVersions(row.oprt_std_id),
      [row.oprt_std_id],
    ),
    resource = useAdminRead(read);
  const dense = useSharedPreferences((state) => state.dense);
  return (
    <section data-testid="param-versions">
      <h3>{row.oprt_std_nm} 적용 이력</h3>
      <ErrorBanner
        error={resource.error}
        onRetry={() => {
          void resource.reload().catch(() => {});
        }}
      />
      {resource.loading && <Skeleton />}
      {resource.data && (
        <DataTable
          rows={resource.data}
          total={resource.data.length}
          rowKey={(r) => r.aply_bgn_ym}
          columns={[
            {
              key: "month",
              label: "적용 시작월",
              kind: "date",
              render: (r) => r.aply_bgn_ym,
            },
            {
              key: "value",
              label: "값(단위)",
              kind: "number",
              render: paramDisplay,
            },
          ]}
          dense={dense}
        />
      )}
    </section>
  );
}
function ParamValueDialog({
  row,
  onCancel,
  reload,
  onDirtyChange,
}: {
  row: OperatingParam;
  onCancel: () => void;
  reload: () => Promise<unknown>;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [value, setValue] = useState(""),
    [month, setMonth] = useState(""),
    [touched, setTouched] = useState(false);
  const valueInput = useRef<HTMLInputElement>(null);
  const monthErrorId = useId();
  const boundaryRead = useCallback(
      () =>
        readData<{ month: string; status: string }[]>(
          "/api/month-close?from=0001-01&to=9999-12",
        ),
      [],
    ),
    boundary = useAdminRead(boundaryRead);
  const versionsRead = useCallback(
      () => listParamVersions(row.oprt_std_id),
      [row.oprt_std_id],
    ),
    versions = useAdminRead(versionsRead);
  const write = useAdminWrite(async () => {
    await reload();
    await versions.reload();
  });
  const closed = boundary.data
    ?.filter((r) => r.status === "CLOSED")
    .map((r) => r.month)
    .sort()
    .at(-1);
  const minMonth = closed
    ? closed.endsWith("-12")
      ? `${String(Number(closed.slice(0, 4)) + 1).padStart(4, "0")}-01`
      : `${closed.slice(0, 4)}-${String(Number(closed.slice(5)) + 1).padStart(2, "0")}`
    : undefined;
  const candidates = versions.data
    ?.filter((r) => r.aply_bgn_ym <= month)
    .sort((a, b) => b.aply_bgn_ym.localeCompare(a.aply_bgn_ym));
  const source = candidates?.[0] ?? versions.data?.at(-1) ?? row;
  const valid = validParamDraft(value, source),
    monthValid = isMonth(month) && (!closed || month > closed);
  const monthError =
    write.error?.field === "month"
      ? write.error.message
      : touched && !monthValid
        ? "마감된 월 이후의 올바른 적용월을 입력하세요."
        : null;
  const blocked =
    boundary.loading ||
    !!boundary.error ||
    !boundary.data ||
    versions.loading ||
    !!versions.error ||
    write.uncertain;
  return (
    <AdminWorkDialog
      title="값 변경"
      onDirtyChange={onDirtyChange}
      onCancel={onCancel}
      onSave={() => {
        setTouched(true);
        if (!valid) {
          valueInput.current?.focus();
          return;
        }
        if (valid && monthValid)
          void write.save(
            () =>
              saveParamVersion(
                row.oprt_std_id,
                month,
                source.num_val === null
                  ? { txt_val: value }
                  : { num_val: value },
              ),
            onCancel,
          );
      }}
      dirty={!!value || !!month}
      pending={write.pending}
      blocked={blocked}
      error={write.error}
      onRetry={() => {
        void write.recover();
      }}
    >
      <p>
        {row.oprt_std_nm} · {source.unit_nm} · {row.use_plc_desc}
      </p>
      <ErrorBanner
        error={boundary.error}
        onRetry={() => {
          void boundary.reload().catch(() => {});
        }}
      />
      <ErrorBanner
        error={versions.error}
        onRetry={() => {
          void versions.reload().catch(() => {});
        }}
      />
      <TextInput
        ref={valueInput}
        label="새 값"
        name={source.num_val === null ? "txt_val" : "num_val"}
        required
        value={value}
        disabled={write.pending}
        maxLength={source.num_val === null ? 200 : undefined}
        onBlur={() => setTouched(true)}
        onValueChange={(v) => {
          write.clear();
          setValue(v);
        }}
        error={
          touched && !valid
            ? "올바른 값을 입력하세요."
            : write.error?.field ===
                (source.num_val === null ? "txt_val" : "num_val")
              ? write.error.message
              : null
        }
      />
      <label>
        적용 시작월 *
        <input
          name="month"
          aria-label="적용 시작월"
          type="month"
          min={minMonth}
          onInvalid={() => setTouched(true)}
          required
          value={month}
          disabled={write.pending || boundary.loading || !!boundary.error}
          aria-invalid={!!monthError}
          aria-describedby={monthError ? monthErrorId : undefined}
          onBlur={() => setTouched(true)}
          onChange={(e) => {
            write.clear();
            setMonth(e.target.value);
          }}
        />
      </label>
      {monthError && (
        <p id={monthErrorId} role="alert">
          {monthError}
        </p>
      )}
      <p>
        {closed
          ? `${closed} 이후 월만 저장할 수 있습니다.`
          : "마감 경계를 확인한 뒤 저장할 수 있습니다."}
      </p>
    </AdminWorkDialog>
  );
}
export function ParamsTab({
  onDirtyChange,
}: { onDirtyChange?: (dirty: boolean) => void } = {}) {
  const [page, setPage] = useState(1),
    [size, setSize] = useState<20 | 50 | 100>(
      () => useSharedPreferences.getState().pageSize,
    ),
    [editing, setEditing] = useState<OperatingParam | null>(null),
    [history, setHistory] = useState<OperatingParam | null>(null);
  const [historyRevision, setHistoryRevision] = useState(0);
  const dense = useSharedPreferences((state) => state.dense),
    configure = useSharedPreferences((state) => state.configure);
  const read = useCallback(
      () =>
        readData<PageResult<OperatingParam>>(
          `${adminPaths.params}?page=${page}&size=${size}`,
        ),
      [page, size],
    ),
    resource = useAdminRead(read);
  const columns: Column<OperatingParam>[] = [
    { key: "name", label: "항목", render: (r) => r.oprt_std_nm },
    {
      key: "value",
      label: "지금 값(단위)",
      kind: "number",
      render: paramDisplay,
    },
    {
      key: "month",
      label: "적용 시작월",
      kind: "date",
      render: (r) => r.aply_bgn_ym,
    },
    { key: "usage", label: "쓰는 곳", render: (r) => r.use_plc_desc },
    {
      key: "actions",
      label: "동작",
      render: (r) => (
        <>
          <Can perm="UI-27.update">
            <Button onClick={() => setEditing(r)}>값 변경</Button>
          </Can>
          <Button onClick={() => setHistory(r)}>이력 보기</Button>
        </>
      ),
    },
  ];
  return (
    <section className={styles.region}>
      <h2>기준값</h2>
      <ErrorBanner
        error={resource.error}
        onRetry={() => {
          void resource.reload().catch(() => {});
        }}
      />
      {resource.loading && <Skeleton />}
      <DataTable
        rows={resource.data?.items ?? []}
        showEmpty={!resource.loading && !resource.error}
        total={resource.data?.total ?? 0}
        rowKey={(r) => r.oprt_std_id}
        columns={columns}
        dense={dense}
        onDenseChange={(v) => configure({ dense: v })}
      />
      <Pagination
        page={page}
        size={size}
        total={resource.data?.total ?? 0}
        onPage={setPage}
        onSize={(s) => {
          setSize(s);
          setPage(1);
        }}
      />
      {history && (
        <ParamVersions
          key={`${history.oprt_std_id}-${historyRevision}`}
          row={history}
        />
      )}{" "}
      {editing && (
        <ParamValueDialog
          row={editing}
          onDirtyChange={onDirtyChange}
          onCancel={() => setEditing(null)}
          reload={async () => {
            await resource.reload();
            setHistoryRevision((revision) => revision + 1);
          }}
        />
      )}
    </section>
  );
}
