import { useEffect, useState } from "react";
import { Lock } from "lucide-react";
import { useSharedPreferences } from "../shared/preferences";
import {
  Actions,
  Button,
  Can,
  DataTable,
  DateInput,
  Form,
  LockedBanner,
  SuccessToast,
  TextInput,
  type Column,
} from "../../shared";
import { adminApi, type CodeGroup, type CodeRow } from "./adminApi";
import {
  AdminLoadState,
  adminFieldError,
  useAdminLoad,
  useAdminMutation,
} from "./adminState";
import styles from "./Admin.module.css";
import inputStyles from "../../components/Inputs.module.css";

export function CodesTab({
  groups,
  selected,
  onSelect,
  onDirty,
  onSaved,
}: {
  groups: CodeGroup[];
  selected: string;
  onSelect: (id: string) => void;
  onDirty: (dirty: boolean) => void;
  onSaved?: () => Promise<void>;
}) {
  const group = groups.find((row) => row.cd_grp_id === selected);
  return (
    <div className={styles.codes}>
      <aside className={styles.groups} aria-label="그룹 목록">
        <h2 className={styles.groupTitle}>코드 그룹</h2>
        {groups.map((row) => (
          <Button
            key={row.cd_grp_id}
            aria-pressed={selected === row.cd_grp_id}
            onClick={() => onSelect(row.cd_grp_id)}
          >
            <span className={styles.groupName}>
              {row.cd_grp_nm}
              <small>{row.cd_grp_id}</small>
            </span>
            {row.cd_grp_se_cd === "SYSTEM" ? (
              <>
                <Lock size={16} aria-hidden="true" />
                <span className={styles.groupMeta}>SYSTEM</span>
              </>
            ) : (
              <span className={styles.groupMeta}>{row.codeCount}</span>
            )}
          </Button>
        ))}
      </aside>
      <section className={styles.editor}>
        {group ? (
          <CodeEditor
            key={selected}
            group={group}
            onDirty={onDirty}
            onSaved={onSaved}
          />
        ) : (
          <p>조건에 맞는 결과가 없습니다</p>
        )}
      </section>
    </div>
  );
}
export function CodeEditor({
  group,
  onDirty,
  onSaved,
}: {
  group: CodeGroup;
  onDirty: (dirty: boolean) => void;
  onSaved?: () => Promise<void>;
}) {
  const result = useAdminLoad(
    () => adminApi.listManagedCodes(group.cd_grp_id),
    [group.cd_grp_id],
  );
  const [rows, setRows] = useState<CodeRow[]>([]);
  const [original, setOriginal] = useState<CodeRow[]>([]);
  const [toast, setToast] = useState(false);
  const dense = useSharedPreferences((state) => state.dense),
    setDense = useSharedPreferences((state) => state.setDense);
  const [invalid, setInvalid] = useState<Record<string, string>>({});
  useEffect(() => {
    if (result.data) {
      setRows(result.data);
      setOriginal(result.data);
      setInvalid({});
    }
  }, [result.data]);
  const dirty = JSON.stringify(rows) !== JSON.stringify(original);
  useEffect(() => {
    onDirty(dirty);
  }, [dirty, onDirty]);
  const mutation = useAdminMutation(
    async () => {
      await result.reload();
      await adminApi.refreshCodeOptions(group.cd_grp_id);
      await onSaved?.();
      setToast(true);
    },
    async () => {
      await adminApi.listManagedCodes(group.cd_grp_id);
    },
  );
  const system = group.cd_grp_se_cd === "SYSTEM";
  function update(
    index: number,
    field: keyof CodeRow,
    value: string | number | null,
  ) {
    mutation.clear();
    setInvalid({});
    setRows((current) =>
      current.map((row, i) => (i === index ? { ...row, [field]: value } : row)),
    );
  }
  function validate() {
    const errors: Record<string, string> = {};
    rows.forEach((row, i) => {
      if (!row.dtl_cd.trim()) errors[`[${i}].dtl_cd`] = "코드를 입력하세요.";
      if (!row.cd_nm.trim()) errors[`[${i}].cd_nm`] = "이름을 입력하세요.";
    });
    setInvalid(errors);
    const first = Object.keys(errors)[0];
    if (first) {
      Array.from(document.querySelectorAll<HTMLElement>("[name]"))
        .find((e) => e.getAttribute("name") === first)
        ?.focus();
      return false;
    }
    return true;
  }
  function cell(
    field: keyof CodeRow,
    label: string,
    maxLength: number,
    readOnly: (row: CodeRow, i: number) => boolean = () => false,
  ) {
    return (row: CodeRow) => {
      const i = rows.indexOf(row),
        name = `[${i}].${field}`;
      return (
        <TextInput
          label={`${label} ${i + 1}`}
          name={name}
          value={String(row[field] ?? "")}
          maxLength={maxLength}
          required={field === "dtl_cd" || field === "cd_nm"}
          requiredMessage={
            field === "dtl_cd" ? "코드를 입력하세요." : "이름을 입력하세요."
          }
          readOnly={readOnly(row, i)}
          disabled={mutation.pending}
          error={invalid[name] || adminFieldError(mutation.error, name)}
          onChange={(value) => update(i, field, value)}
        />
      );
    };
  }
  const columns: Column<CodeRow>[] = [
    {
      key: "dtl_cd",
      title: "코드",
      kind: "code",
      render: cell("dtl_cd", "코드", 30, (_r, i) => i < original.length),
    },
    { key: "cd_nm", title: "이름", render: cell("cd_nm", "이름", 100) },
    {
      key: "sort_seq",
      title: "순서",
      kind: "number",
      render: (row) => {
        const index = rows.indexOf(row);
        const error = adminFieldError(mutation.error, `[${index}].sort_seq`);
        return (
          <div>
            <input
              aria-label={`순서 ${index + 1}`}
              name={`[${index}].sort_seq`}
              className={inputStyles.input}
              aria-invalid={Boolean(error)}
              aria-describedby={error ? `sort-${index}-error` : undefined}
              type="number"
              step="1"
              required
              disabled={mutation.pending}
              value={row.sort_seq}
              onChange={(e) => {
                if (
                  e.target.value !== "" &&
                  Number.isSafeInteger(e.target.valueAsNumber)
                )
                  update(index, "sort_seq", e.target.valueAsNumber);
              }}
            />
            {error && (
              <span
                role="alert"
                id={`sort-${index}-error`}
                className={inputStyles.error}
              >
                {error}
              </span>
            )}
          </div>
        );
      },
    },
    {
      key: "use_yn",
      title: "사용",
      kind: "status",
      render: (row) => (
        <Can perm="UI-27.delete">
          <input
            type="checkbox"
            aria-label={`사용 ${row.dtl_cd}`}
            checked={row.use_yn === "Y"}
            disabled={
              system || mutation.pending || rows.indexOf(row) >= original.length
            }
            onChange={(e) =>
              update(rows.indexOf(row), "use_yn", e.target.checked ? "Y" : "N")
            }
          />
        </Can>
      ),
    },
    ...([1, 2, 3] as const)
      .filter((n) => group[`attr${n}_nm`])
      .map((n) => ({
        key: `attr${n}_val`,
        title: group[`attr${n}_nm`]!,
        render: cell(`attr${n}_val`, group[`attr${n}_nm`]!, 100, () => system),
      })),
    {
      key: "aply_bgn_dt",
      title: "적용 시작일",
      kind: "date",
      render: (row) => (
        <DateInput
          label={`적용 시작일 ${rows.indexOf(row) + 1}`}
          name={`[${rows.indexOf(row)}].aply_bgn_dt`}
          error={adminFieldError(
            mutation.error,
            `[${rows.indexOf(row)}].aply_bgn_dt`,
          )}
          value={row.aply_bgn_dt ?? ""}
          readOnly={system}
          disabled={mutation.pending}
          onChange={(v) => update(rows.indexOf(row), "aply_bgn_dt", v || null)}
        />
      ),
    },
  ];
  return (
    <>
      <h2>
        {group.cd_grp_nm} · {rows.length}건
      </h2>
      {system && (
        <LockedBanner>
          계산 규칙에 쓰는 시스템 코드입니다. 이름과 순서만 바꿀 수 있고
          추가·사용 중지는 할 수 없습니다.
        </LockedBanner>
      )}
      <AdminLoadState
        loading={result.loading}
        error={result.error || mutation.error}
        retry={() => {
          if (dirty)
            void adminApi
              .listManagedCodes(group.cd_grp_id)
              .then(() => mutation.clear())
              .catch(() => undefined);
          else void result.reload();
        }}
      />
      <Form
        onSubmit={() => {
          if (validate())
            void mutation.run(() =>
              adminApi.saveGroupCodes(group.cd_grp_id, rows),
            );
        }}
      >
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(row) => String(rows.indexOf(row))}
          total={rows.length}
          dense={dense}
          onDenseChange={setDense}
          emptyMessage="조건에 맞는 결과가 없습니다"
          loading={result.loading}
          unavailable={Boolean(result.error)}
        />
        <Actions>
          {!system && (
            <Can perm="UI-27.create">
              <Button
                disabled={
                  result.loading || Boolean(result.error) || mutation.pending
                }
                onClick={() =>
                  setRows((current) => [
                    ...current,
                    {
                      dtl_cd: "",
                      cd_nm: "",
                      sort_seq:
                        Math.max(0, ...current.map((row) => row.sort_seq)) + 1,
                      use_yn: "Y",
                      attr1_val: null,
                      attr2_val: null,
                      attr3_val: null,
                      aply_bgn_dt: null,
                    },
                  ])
                }
              >
                코드 추가
              </Button>
            </Can>
          )}
          <Button
            disabled={mutation.pending}
            onClick={() => {
              setRows(original);
              setInvalid({});
              mutation.clear();
            }}
          >
            취소
          </Button>
          <Can perm="UI-27.update">
            <Button
              variant="primary"
              type="submit"
              pending={mutation.pending}
              disabled={result.loading || Boolean(result.error) || !dirty}
            >
              저장
            </Button>
          </Can>
        </Actions>
      </Form>
      {toast && (
        <SuccessToast
          message="공통코드를 저장했습니다."
          onDismiss={() => setToast(false)}
        />
      )}
    </>
  );
}
