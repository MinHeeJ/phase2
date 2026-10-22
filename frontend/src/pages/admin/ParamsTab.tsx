import { useState } from "react";
import inputStyles from "../../components/Inputs.module.css";
import { api } from "../../services/api";
import { useSharedPreferences } from "../shared/preferences";
import { formatDateTime, isValidMonth } from "../../lib/format";
import {
  Actions,
  Button,
  Can,
  DataTable,
  ErrorBanner,
  Form,
  Modal,
  Pagination,
  QueryBar,
  SelectInput,
  TextInput,
  type Column,
} from "../../shared";
import {
  adminApi,
  type CodeChange,
  type CodeGroup,
  type OperatingParam,
  type PageSize,
} from "./adminApi";
import {
  AdminLoadState,
  adminFieldError,
  useAdminDiscard,
  useAdminLoad,
  useAdminMutation,
} from "./adminState";

export function ParamsTab() {
  const defaultSize = useSharedPreferences((state) => state.pageSize);
  const dense = useSharedPreferences((state) => state.dense),
    setDense = useSharedPreferences((state) => state.setDense);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState<PageSize>(defaultSize);
  const [selected, setSelected] = useState<OperatingParam>();
  const [history, setHistory] = useState<string>();
  const [revision, setRevision] = useState(0);
  const result = useAdminLoad(
    () => adminApi.listCurrentParams(page, size),
    [page, size],
  );
  const columns: Column<OperatingParam>[] = [
    { key: "name", title: "항목", render: (r) => r.oprt_std_nm },
    {
      key: "value",
      title: "지금 값(단위)",
      kind: "number",
      render: paramValue,
    },
    {
      key: "month",
      title: "적용 시작월",
      kind: "date",
      render: (r) => r.aply_bgn_ym,
    },
    { key: "place", title: "쓰는 곳", render: (r) => r.use_plc_desc ?? "–" },
    {
      key: "action",
      title: "동작",
      render: (r) => (
        <Actions>
          <Can perm="UI-27.update">
            <Button onClick={() => setSelected(r)}>값 변경</Button>
          </Can>
          <Button onClick={() => setHistory(r.oprt_std_id)}>이력 보기</Button>
        </Actions>
      ),
    },
  ];
  return (
    <>
      <AdminLoadState
        loading={result.loading}
        error={result.error}
        retry={result.reload}
      />
      <DataTable
        columns={columns}
        rows={result.data?.items ?? []}
        rowKey={(r) => r.oprt_std_id}
        loading={result.loading}
        unavailable={Boolean(result.error)}
        total={result.data?.total ?? 0}
        dense={dense}
        onDenseChange={setDense}
        emptyMessage="조건에 맞는 결과가 없습니다"
      />
      <Pagination
        page={page}
        size={size}
        total={result.data?.total ?? 0}
        onPage={setPage}
        onSize={(s) => {
          setSize(s);
          setPage(1);
        }}
      />
      {history && (
        <ParamVersions key={`${history}-${revision}`} paramKey={history} />
      )}
      {selected && (
        <ParamDialog
          row={selected}
          onClose={() => setSelected(undefined)}
          onSaved={async () => {
            await result.reload();
            setHistory(selected.oprt_std_id);
            setRevision((v) => v + 1);
          }}
        />
      )}
    </>
  );
}
function paramValue(row: OperatingParam) {
  return `${row.num_val ?? row.txt_val ?? "–"}${row.unit_nm ? ` ${row.unit_nm}` : ""}`;
}
export function ParamVersions({ paramKey }: { paramKey: string }) {
  const dense = useSharedPreferences((state) => state.dense);
  const result = useAdminLoad(
    () => adminApi.listParamVersions(paramKey),
    [paramKey],
  );
  return (
    <section>
      <h2>{paramKey} 적용 이력</h2>
      <AdminLoadState
        loading={result.loading}
        error={result.error}
        retry={result.reload}
      />
      <DataTable
        columns={[
          {
            key: "month",
            title: "적용 시작월",
            kind: "date",
            render: (r: OperatingParam) => r.aply_bgn_ym,
          },
          {
            key: "value",
            title: "값(단위)",
            kind: "number",
            render: paramValue,
          },
        ]}
        rows={result.data ?? []}
        total={result.data?.length ?? 0}
        rowKey={(r) => r.aply_bgn_ym}
        loading={result.loading}
        unavailable={Boolean(result.error)}
        dense={dense}
        emptyMessage="조건에 맞는 결과가 없습니다"
      />
    </section>
  );
}
export function ParamDialog({
  row,
  onClose,
  onSaved,
}: {
  row: OperatingParam;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const [value, setValue] = useState(String(row.num_val ?? row.txt_val ?? ""));
  const [month, setMonth] = useState("");
  const [touched, setTouched] = useState(false);
  const closes = useAdminLoad(
    () =>
      api<{ month: string; status: string }[]>(
        "/api/month-close?from=0001-01&to=9999-12",
      ),
    [],
  );
  const closed = closes.data
    ?.filter((r) => r.status === "CLOSED")
    .reduce((max, r) => (r.month > max ? r.month : max), "");
  const numeric = row.num_val !== null;
  const validNumber =
    /^(?:0|[1-9]\d{0,13})(?:\.\d{1,4})?$/.test(value) &&
    Number(value) >= 0 &&
    (!["%", "%p"].includes(row.unit_nm ?? "") || Number(value) <= 100);
  const validValue = numeric
    ? validNumber
    : value.trim().length > 0 && value.length <= 200;
  const validMonth = isValidMonth(month) && (!closed || month > closed);
  const discard = useAdminDiscard(
    value !== String(row.num_val ?? row.txt_val ?? "") || month !== "",
  );
  const mutation = useAdminMutation(async () => {
    await onSaved();
    onClose();
  }, onSaved);
  const field = numeric ? "num_val" : "txt_val";
  const monthError =
    adminFieldError(mutation.error, "month") ||
    (touched && !validMonth
      ? "마감된 월 이후의 올바른 적용월을 선택하세요."
      : undefined);
  return (
    <>
      <Modal
        title="값 변경"
        onClose={() => {
          if (!mutation.pending) discard.request(onClose);
        }}
      >
        <p>
          {row.oprt_std_nm} · {row.unit_nm ?? "–"} · {row.use_plc_desc ?? "–"}
        </p>
        <AdminLoadState
          loading={closes.loading}
          error={closes.error}
          retry={closes.reload}
        />
        {mutation.error && (
          <ErrorBanner error={mutation.error} onRetry={() => void onSaved()} />
        )}
        <Form
          onSubmit={() => {
            setTouched(true);
            if (validValue && validMonth && closes.data && !closes.error)
              void mutation.run(() =>
                adminApi.saveParamVersion(
                  row.oprt_std_id,
                  month,
                  numeric ? { num_val: value } : { txt_val: value },
                ),
              );
          }}
        >
          <div onBlur={() => setTouched(true)}>
            <TextInput
              label="새 값"
              name={field}
              required
              maxLength={numeric ? undefined : 200}
              value={value}
              disabled={mutation.pending}
              error={
                adminFieldError(mutation.error, field) ||
                (touched && !validValue ? "올바른 값을 입력하세요." : undefined)
              }
              onChange={(v) => {
                mutation.clear();
                setValue(v);
              }}
            />
          </div>
          <label>
            적용 시작월 *
            <input
              name="month"
              className={inputStyles.input}
              aria-label="적용 시작월"
              type="month"
              required
              value={month}
              min={closed ? nextMonth(closed) : "0001-01"}
              max="9999-12"
              disabled={
                closes.loading || Boolean(closes.error) || mutation.pending
              }
              aria-invalid={Boolean(monthError)}
              aria-describedby={monthError ? "param-month-error" : undefined}
              onBlur={() => setTouched(true)}
              onChange={(e) => {
                mutation.clear();
                setMonth(e.target.value);
              }}
            />
          </label>
          {monthError && (
            <p
              role="alert"
              id="param-month-error"
              className={inputStyles.error}
            >
              {monthError}
            </p>
          )}
          <Actions>
            <Button
              disabled={mutation.pending}
              onClick={() => discard.request(onClose)}
            >
              취소
            </Button>
            <Button
              type="submit"
              variant="primary"
              pending={mutation.pending}
              disabled={
                closes.loading ||
                Boolean(closes.error) ||
                !closes.data ||
                !validMonth ||
                !validValue
              }
            >
              저장
            </Button>
          </Actions>
        </Form>
      </Modal>
      {discard.dialog}
    </>
  );
}
function nextMonth(month: string) {
  const [y, m] = month.split("-").map(Number);
  return m === 12
    ? `${String(y + 1).padStart(4, "0")}-01`
    : `${y}-${String(m + 1).padStart(2, "0")}`;
}
export function CodeHistoryTab({ groups }: { groups: CodeGroup[] }) {
  const dense = useSharedPreferences((state) => state.dense),
    setDense = useSharedPreferences((state) => state.setDense);
  const [filter, setFilter] = useState("");
  const [group, setGroup] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState<PageSize>(50);
  const result = useAdminLoad(
    () => adminApi.listCodeHistory(group, page, size),
    [group, page, size],
  );
  const typeNames = {
    ADD: "추가",
    UPDATE: "수정",
    DISABLE: "사용 중지",
    ENABLE: "다시 사용",
  };
  const columns: Column<CodeChange>[] = [
    {
      key: "at",
      title: "일시",
      kind: "date",
      render: (r) => formatDateTime(r.chg_at),
    },
    {
      key: "group",
      title: "그룹",
      render: (r) => (r.cd_grp_id === "PARAM" ? "기준값" : r.cd_grp_nm),
    },
    {
      key: "code",
      title: "코드",
      kind: "code",
      render: (r) => r.dtl_cd ?? "–",
    },
    { key: "type", title: "구분", render: (r) => typeNames[r.cd_chg_ty_cd] },
    { key: "item", title: "항목", render: (r) => r.item_nm },
    { key: "before", title: "이전", render: (r) => r.bfr_val ?? "–" },
    { key: "after", title: "이후", render: (r) => r.aft_val ?? "–" },
    {
      key: "user",
      title: "변경자",
      render: (r) =>
        r.chg_user_id === null ? "기본 사용자" : String(r.chg_user_id),
    },
  ];
  return (
    <>
      <QueryBar
        onSearch={() => {
          setGroup(filter);
          setPage(1);
          if (filter === group && page === 1) void result.reload();
        }}
        onReset={() => {
          setFilter("");
        }}
      >
        <SelectInput
          label="그룹"
          value={filter}
          onChange={setFilter}
          options={[
            ...groups.map((g) => ({ value: g.cd_grp_id, label: g.cd_grp_nm })),
            { value: "PARAM", label: "기준값" },
          ]}
        />
      </QueryBar>
      <AdminLoadState
        loading={result.loading}
        error={result.error}
        retry={result.reload}
      />
      <DataTable
        columns={columns}
        rows={result.data?.items ?? []}
        rowKey={(r) => `${r.chg_at}-${result.data!.items.indexOf(r)}`}
        loading={result.loading}
        unavailable={Boolean(result.error)}
        total={result.data?.total ?? 0}
        dense={dense}
        onDenseChange={setDense}
        emptyMessage="조건에 맞는 결과가 없습니다"
      />
      <Pagination
        page={page}
        size={size}
        total={result.data?.total ?? 0}
        onPage={setPage}
        onSize={(s) => {
          setSize(s);
          setPage(1);
        }}
      />
    </>
  );
}
