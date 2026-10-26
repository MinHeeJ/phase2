import { useCallback, useEffect, useRef, useState } from "react";
import { getMonthClose, useSharedRead } from "../shared/sharedApi";
import { seoulCurrentMonth } from "../shared/SharedPlaceholderScreen";
import { Button, Dialog, Notice, Skeleton, Actions } from "../../shared/ui";
import { TextInput, MonthInput } from "../../shared/inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/permissions";
import { DataTable, Pagination } from "../../shared/table";
import { usePreferences } from "../../shared/preferences";
import {
  collectAdminPages,
  listCurrentParams,
  listParamVersions,
  saveParamVersion,
  type OperatingParam,
} from "./adminApi";
import { useAdminMutation } from "./adminForms";
import type { AdminRegionProps } from "./UI27";
import styles from "./admin.module.css";

const loadClosing = () => getMonthClose("0001-01", "9999-12");
export function nextAdminMonth(value: string) {
  const [year, month] = value.split("-").map(Number);
  return month === 12
    ? `${String(year + 1).padStart(4, "0")}-01`
    : `${String(year).padStart(4, "0")}-${String(month + 1).padStart(2, "0")}`;
}
export function validAdminParam(value: string, source: OperatingParam) {
  if (source.num_val == null)
    return (
      source.txt_val != null && value.trim().length > 0 && value.length <= 200
    );
  if (!/^\d{1,14}(?:\.\d{1,4}0*)?$/.test(value)) return false;
  return !["%", "%p"].includes(source.unit_nm ?? "") || Number(value) <= 100;
}
function AdminParamDialog({
  row,
  onDirty,
  onClose,
  onSaved,
}: {
  row: OperatingParam;
  onDirty: (value: boolean) => void;
  onClose: () => void;
  onSaved: () => void;
}) {
  const closing = useSharedRead(loadClosing);
  const loadVersions = useCallback(
    () =>
      collectAdminPages((page) =>
        listParamVersions(row.oprt_std_id, page, 100),
      ),
    [row.oprt_std_id],
  );
  const versions = useSharedRead(loadVersions);
  const source = versions.data?.[0];
  const [month, setMonth] = useState(seoulCurrentMonth());
  const [value, setValue] = useState("");
  const [touched, setTouched] = useState(false);
  const mutation = useAdminMutation();
  const form = useRef<HTMLFormElement>(null);
  const closed = closing.data
    ?.filter((item) => item.status === "CLOSED")
    .map((item) => item.month)
    .sort()
    .at(-1);
  const validMonth =
    /^\d{4}-(0[1-9]|1[0-2])$/.test(month) &&
    !month.startsWith("0000") &&
    (!closed || month > closed);
  const ready =
    !!source &&
    !!closing.data &&
    !closing.loading &&
    !closing.error &&
    !versions.loading &&
    !versions.error;
  const valid = !!source && validAdminParam(value, source);
  useEffect(() => {
    if (source) setValue(String(source.num_val ?? source.txt_val ?? ""));
  }, [source]);
  return (
    <Dialog
      title="값 변경"
      onClose={() => {
        if (!mutation.pending) onClose();
      }}
    >
      <p>
        {row.oprt_std_nm} · {row.unit_nm} · {row.use_plc_desc}
      </p>
      <ErrorBanner error={closing.error} onRetry={closing.retry} />
      <ErrorBanner error={versions.error} onRetry={versions.retry} />
      <ErrorBanner error={mutation.error} onRetry={mutation.clear} />
      {(closing.loading || versions.loading) && (
        <Skeleton label="적용월 기준 불러오는 중" />
      )}
      {closed && (
        <Notice>
          마지막 마감월: {closed}. 다음 달부터 적용할 수 있습니다.
        </Notice>
      )}
      <form
        data-testid="admin-param-edit-form"
        ref={form}
        className={styles.form}
        onSubmit={async (event) => {
          event.preventDefault();
          setTouched(true);
          if (!ready || !valid || !validMonth || !source) {
            form.current
              ?.querySelector<HTMLInputElement>(
                !valid ? '[name="num_val"],[name="txt_val"]' : '[name="month"]',
              )
              ?.focus();
            return;
          }
          const payload =
            source.num_val != null ? { num_val: value } : { txt_val: value };
          if (
            await mutation.run(
              () => saveParamVersion(row.oprt_std_id, month, payload),
              form.current,
            )
          )
            onSaved();
        }}
      >
        <fieldset
          className={styles.fieldset}
          disabled={!ready || mutation.pending}
        >
          <TextInput
            name={source?.num_val != null ? "num_val" : "txt_val"}
            label="새 값"
            required
            maxLength={source?.num_val != null ? undefined : 200}
            value={value}
            error={
              mutation.fieldError(
                source?.num_val != null ? "num_val" : "txt_val",
              ) || (touched && !valid ? "올바른 값을 입력하세요." : undefined)
            }
            onBlur={() => setTouched(true)}
            onChange={(event) => {
              setValue(event.target.value);
              onDirty(true);
            }}
          />
          <MonthInput
            name="month"
            label="적용 시작월"
            required
            disabled={closed === "9999-12"}
            value={month}
            min={
              closed && closed !== "9999-12"
                ? nextAdminMonth(closed)
                : "0001-01"
            }
            max="9999-12"
            error={
              mutation.fieldError("month") ||
              (!validMonth ? "마감월 다음 달 이후를 선택하세요." : undefined)
            }
            onChange={(event) => {
              setMonth(event.target.value);
              onDirty(true);
            }}
          />
        </fieldset>
        <Actions>
          <Button disabled={mutation.pending} onClick={onClose}>
            취소
          </Button>
          <Can perm="UI-27.update">
            <Button
              type="submit"
              primary
              pending={mutation.pending}
              disabled={!ready || !validMonth}
            >
              저장
            </Button>
          </Can>
        </Actions>
      </form>
    </Dialog>
  );
}
export function AdminParamRegion({
  onDirty,
  onSuccess,
  guard,
}: AdminRegionProps) {
  const preferences = usePreferences();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState<20 | 50 | 100>(preferences.pageSize);
  const load = useCallback(() => listCurrentParams(page, size), [page, size]);
  const read = useSharedRead(load);
  const [editing, setEditing] = useState<OperatingParam>();
  const [historyKey, setHistoryKey] = useState<string>();
  const loadVersions = useCallback(
    async () => ({
      key: historyKey,
      items: await collectAdminPages((page) =>
        listParamVersions(historyKey!, page, 100),
      ),
    }),
    [historyKey],
  );
  const versions = useSharedRead(loadVersions, !!historyKey);
  const historyRows =
    versions.data && versions.data.key === historyKey
      ? versions.data.items
      : [];
  return (
    <section
      data-testid="admin-param-region"
      className={styles.panel}
      aria-label="기준값"
    >
      <div className={styles.regionHeading}>
        <div>
          <h2>기준값</h2>
          <p>현재 적용 중인 값과 적용월별 변경 이력을 확인합니다.</p>
        </div>
      </div>
      <ErrorBanner error={read.error} onRetry={read.retry} />
      <DataTable
        loading={read.loading}
        error={!!read.error}
        rows={read.data?.items ?? []}
        rowKey={(row) => row.oprt_std_id}
        total={read.data?.total ?? 0}
        dense={preferences.dense}
        columns={[
          { key: "name", title: "항목", render: (row) => row.oprt_std_nm },
          {
            key: "value",
            title: "지금 값",
            kind: "number",
            render: (row) =>
              `${row.num_val ?? row.txt_val ?? "–"} ${row.unit_nm ?? ""}`,
          },
          {
            key: "month",
            title: "적용 시작월",
            kind: "date",
            render: (row) => row.aply_bgn_ym,
          },
          {
            key: "usage",
            title: "쓰는 곳",
            render: (row) => row.use_plc_desc ?? "–",
          },
          {
            key: "actions",
            title: "동작",
            render: (row) => (
              <>
                <Can perm="UI-27.update">
                  <Button onClick={() => setEditing(row)}>값 변경</Button>
                </Can>
                <Button onClick={() => setHistoryKey(row.oprt_std_id)}>
                  이력 보기
                </Button>
              </>
            ),
          },
        ]}
      />
      <Pagination
        page={page}
        size={size}
        total={read.data?.total ?? 0}
        onPage={setPage}
        onSize={setSize}
      />
      {historyKey && (
        <section aria-label="기준값 적용 이력">
          <h2>{historyKey} 적용 이력</h2>
          <ErrorBanner error={versions.error} onRetry={versions.retry} />
          {versions.loading ? (
            <Skeleton />
          ) : (
            <DataTable
              rows={historyRows}
              error={!!versions.error}
              total={historyRows.length}
              rowKey={(row) => `${row.oprt_std_id}-${row.aply_bgn_ym}`}
              columns={[
                {
                  key: "month",
                  title: "적용 시작월",
                  kind: "date",
                  render: (row) => row.aply_bgn_ym,
                },
                {
                  key: "value",
                  title: "값",
                  render: (row) =>
                    `${row.num_val ?? row.txt_val ?? "–"}${row.unit_nm ? ` ${row.unit_nm}` : ""}`,
                },
              ]}
            />
          )}
        </section>
      )}
      {editing && (
        <AdminParamDialog
          row={editing}
          onDirty={onDirty}
          onClose={() =>
            guard(() => {
              setEditing(undefined);
              onDirty(false);
            })
          }
          onSaved={() => {
            setEditing(undefined);
            onDirty(false);
            read.retry();
            versions.retry();
            onSuccess("기준값을 저장했습니다.");
          }}
        />
      )}
    </section>
  );
}
