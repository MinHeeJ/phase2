import { useEffect, useState } from "react";
import { api } from "../../services/api";
import { useSharedPreferences } from "../shared/preferences";
import type { CodeOption } from "../shared/api";
import {
  Actions,
  Button,
  Can,
  DataTable,
  ErrorBanner,
  Form,
  Modal,
  Pagination,
  SelectInput,
  TextInput,
  type Column,
} from "../../shared";
import {
  adminApi,
  type CodeGroup,
  type ExcelMap,
  type ExcelMapCreate,
  type PageSize,
} from "./adminApi";
import {
  AdminLoadState,
  adminFieldError,
  useAdminDiscard,
  useAdminLoad,
  useAdminMutation,
} from "./adminState";

export function ExcelMapsTab({
  groups,
  onDirty,
}: {
  groups: CodeGroup[];
  onDirty: (dirty: boolean) => void;
}) {
  const defaultSize = useSharedPreferences((state) => state.pageSize);
  const dense = useSharedPreferences((state) => state.dense),
    setDense = useSharedPreferences((state) => state.setDense);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState<PageSize>(defaultSize);
  const [dialog, setDialog] = useState(false);
  const result = useAdminLoad(
    () => adminApi.listExcelMaps(page, size),
    [page, size],
  );
  const [drafts, setDrafts] = useState<Record<string, ExcelMap>>({});
  const dirty = Object.keys(drafts).length > 0;
  useEffect(() => onDirty(dirty), [dirty, onDirty]);
  const discard = useAdminDiscard(dirty);
  const mutation = useAdminMutation(result.reload, async () => {
    await adminApi.listExcelMaps(page, size);
  });
  function draft(row: ExcelMap, field: keyof ExcelMap, value: string) {
    mutation.clear();
    setDrafts((current) => ({
      ...current,
      [row.map_id]: { ...(current[row.map_id] ?? row), [field]: value },
    }));
  }
  const columns: Column<ExcelMap>[] = [
    {
      key: "excel_lbl_nm",
      title: "엑셀 표기",
      render: (row) => row.excel_lbl_nm,
    },
    {
      key: "target",
      title: "바꾸는 값 (그룹명 · 코드명)",
      render: (row) => `${row.cd_grp_nm} · ${row.cd_nm}`,
    },
    {
      key: "map_role_cd",
      title: "주/보조",
      render: (row) => (
        <SelectInput
          label={`주/보조 ${row.map_id}`}
          value={(drafts[row.map_id] ?? row).map_role_cd}
          options={[
            { value: "MAIN", label: "주" },
            { value: "SUB", label: "보조" },
          ]}
          disabled={mutation.pending}
          onChange={(v) => draft(row, "map_role_cd", v)}
        />
      ),
    },
    {
      key: "rmrk_desc",
      title: "메모",
      render: (row) => (
        <TextInput
          label={`메모 ${row.map_id}`}
          name="rmrk_desc"
          value={(drafts[row.map_id] ?? row).rmrk_desc ?? ""}
          maxLength={200}
          disabled={mutation.pending}
          error={adminFieldError(mutation.error, "rmrk_desc")}
          onChange={(v) => draft(row, "rmrk_desc", v)}
        />
      ),
    },
    {
      key: "use_yn",
      title: "사용",
      kind: "status",
      render: (row) => (
        <Can perm="UI-27.delete">
          <input
            aria-label={`사용 ${row.map_id}`}
            type="checkbox"
            checked={(drafts[row.map_id] ?? row).use_yn === "Y"}
            disabled={mutation.pending}
            onChange={(e) => draft(row, "use_yn", e.target.checked ? "Y" : "N")}
          />
        </Can>
      ),
    },
    {
      key: "actions",
      title: "동작",
      render: (row) => (
        <Actions>
          <Button
            disabled={!drafts[row.map_id] || mutation.pending}
            onClick={() =>
              setDrafts((current) => {
                const next = { ...current };
                delete next[row.map_id];
                return next;
              })
            }
          >
            취소
          </Button>
          <Can perm="UI-27.update">
            <Button
              disabled={!drafts[row.map_id]}
              pending={mutation.pending}
              onClick={() => {
                const value = drafts[row.map_id];
                void mutation.run(async () => {
                  await adminApi.updateExcelMap(row.map_id, {
                    map_role_cd: value.map_role_cd,
                    ...(value.rmrk_desc !== null
                      ? { rmrk_desc: value.rmrk_desc }
                      : {}),
                    use_yn: value.use_yn,
                  });
                  setDrafts((current) => {
                    const next = { ...current };
                    delete next[row.map_id];
                    return next;
                  });
                });
              }}
            >
              저장
            </Button>
          </Can>
        </Actions>
      ),
    },
  ];
  return (
    <>
      <Actions>
        <Can perm="UI-27.create">
          <Button onClick={() => setDialog(true)}>대응 추가</Button>
        </Can>
      </Actions>
      <AdminLoadState
        loading={result.loading}
        error={result.error || mutation.error}
        retry={() => {
          if (dirty)
            void adminApi
              .listExcelMaps(page, size)
              .then(() => mutation.clear())
              .catch(() => undefined);
          else void result.reload();
        }}
      />
      <DataTable
        columns={columns}
        rows={result.data?.items ?? []}
        rowKey={(r) => r.map_id}
        total={result.data?.total ?? 0}
        dense={dense}
        onDenseChange={setDense}
        emptyMessage="조건에 맞는 결과가 없습니다"
        loading={result.loading}
        unavailable={Boolean(result.error)}
      />
      <Pagination
        page={page}
        size={size}
        total={result.data?.total ?? 0}
        onPage={(p) =>
          discard.request(() => {
            setDrafts({});
            setPage(p);
          })
        }
        onSize={(s) =>
          discard.request(() => {
            setDrafts({});
            setSize(s);
            setPage(1);
          })
        }
      />
      {dialog && (
        <ExcelMapDialog
          groups={groups}
          onClose={() => setDialog(false)}
          onSaved={result.reload}
        />
      )}
      {discard.dialog}
    </>
  );
}
export function ExcelMapDialog({
  groups,
  onClose,
  onSaved,
}: {
  groups: CodeGroup[];
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const [body, setBody] = useState({
    excel_lbl_nm: "",
    trns_cd_grp_id: "",
    trns_cd: "",
    map_role_cd: "",
    rmrk_desc: "",
  });
  const options = useAdminLoad(
    () =>
      body.trns_cd_grp_id
        ? api<CodeOption[]>(
            `/api/codes/${encodeURIComponent(body.trns_cd_grp_id)}`,
          )
        : Promise.resolve<CodeOption[]>([]),
    [body.trns_cd_grp_id],
  );
  const discard = useAdminDiscard(Object.values(body).some(Boolean));
  const mutation = useAdminMutation(async () => {
    await onSaved();
    onClose();
  }, onSaved);
  function change(field: keyof typeof body, value: string) {
    mutation.clear();
    setBody((current) => ({
      ...current,
      [field]: value,
      ...(field === "trns_cd_grp_id" ? { trns_cd: "" } : {}),
    }));
  }
  return (
    <>
      <Modal
        title="대응 추가"
        onClose={() => {
          if (!mutation.pending) discard.request(onClose);
        }}
      >
        {mutation.error && (
          <ErrorBanner error={mutation.error} onRetry={() => void onSaved()} />
        )}
        <Form
          onSubmit={() =>
            void mutation.run(() =>
              adminApi.createExcelMap(body as ExcelMapCreate),
            )
          }
        >
          <TextInput
            label="엑셀 표기"
            name="excel_lbl_nm"
            required
            requiredMessage="엑셀 글자를 입력하세요."
            maxLength={100}
            value={body.excel_lbl_nm}
            disabled={mutation.pending}
            error={adminFieldError(mutation.error, "excel_lbl_nm")}
            onChange={(v) => change("excel_lbl_nm", v)}
          />
          <SelectInput
            label="그룹"
            name="trns_cd_grp_id"
            required
            requiredMessage="대응할 코드를 고르세요."
            value={body.trns_cd_grp_id}
            disabled={mutation.pending}
            options={groups.map((g) => ({
              value: g.cd_grp_id,
              label: g.cd_grp_nm,
            }))}
            error={adminFieldError(mutation.error, "trns_cd_grp_id")}
            onChange={(v) => change("trns_cd_grp_id", v)}
          />
          <AdminLoadState
            loading={options.loading}
            error={options.error}
            retry={options.reload}
          />
          <SelectInput
            label="코드"
            name="trns_cd"
            required
            requiredMessage="대응할 코드를 고르세요."
            value={body.trns_cd}
            disabled={
              !body.trns_cd_grp_id ||
              options.loading ||
              Boolean(options.error) ||
              mutation.pending
            }
            options={(options.data ?? []).map((c) => ({
              value: c.code,
              label: c.name,
            }))}
            error={adminFieldError(mutation.error, "trns_cd")}
            onChange={(v) => change("trns_cd", v)}
          />
          <SelectInput
            label="주/보조"
            name="map_role_cd"
            required
            value={body.map_role_cd}
            disabled={mutation.pending}
            options={[
              { value: "MAIN", label: "주" },
              { value: "SUB", label: "보조" },
            ]}
            error={adminFieldError(mutation.error, "map_role_cd")}
            onChange={(v) => change("map_role_cd", v)}
          />
          <TextInput
            label="메모"
            name="rmrk_desc"
            multiline
            maxLength={200}
            value={body.rmrk_desc}
            disabled={mutation.pending}
            error={adminFieldError(mutation.error, "rmrk_desc")}
            onChange={(v) => change("rmrk_desc", v)}
          />
          <Actions>
            <Button
              disabled={mutation.pending}
              onClick={() => discard.request(onClose)}
            >
              취소
            </Button>
            <Button
              variant="primary"
              type="submit"
              pending={mutation.pending}
              disabled={options.loading || Boolean(options.error)}
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
