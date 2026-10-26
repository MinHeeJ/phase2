import { useCallback, useEffect, useRef, useState } from "react";
import { useSharedRead } from "../shared/sharedApi";
import { Button, Notice } from "../../shared/ui";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/permissions";
import { usePreferences } from "../../shared/preferences";
import { DataTable } from "../../shared/table";
import {
  collectAdminPages,
  listManagedCodes,
  saveGroupCodes,
  type CodeGroup,
  type ManagedCode,
} from "./adminApi";
import { useAdminMutation } from "./adminForms";
import type { AdminRegionProps } from "./UI27";
import styles from "./admin.module.css";

export function AdminCodeRegion({
  group,
  onDirty,
  onSuccess,
  onSaved,
}: AdminRegionProps & { group: CodeGroup; onSaved: () => void }) {
  const load = useCallback(
    () =>
      collectAdminPages((page) => listManagedCodes(group.cd_grp_id, page, 100)),
    [group.cd_grp_id],
  );
  const read = useSharedRead(load);
  const mutation = useAdminMutation();
  const preferences = usePreferences();
  const [rows, setRows] = useState<ManagedCode[]>([]);
  const [original, setOriginal] = useState<ManagedCode[]>([]);
  const [touched, setTouched] = useState<Record<string, boolean>>({});

  const form = useRef<HTMLFormElement>(null);
  useEffect(() => {
    if (read.data) {
      setRows(read.data);
      setOriginal(read.data);
      setTouched({});
      onDirty(false);
    }
  }, [read.data, onDirty]);
  const system = group.cd_grp_se_cd === "SYSTEM";
  const dirty = JSON.stringify(rows) !== JSON.stringify(original);

  function edit(index: number, patch: Partial<ManagedCode>) {
    setRows((old) =>
      old.map((row, i) => (i === index ? { ...row, ...patch } : row)),
    );
    onDirty(true);
  }
  function input(
    index: number,
    key: keyof ManagedCode,
    maxLength?: number,
    readonly = false,
    type = "text",
  ) {
    const name = `[${index}].${key}`;
    const row = rows[index];
    const value = row[key];
    const required = key === "dtl_cd" || key === "cd_nm" || key === "sort_seq";
    const empty = required && !String(value ?? "").trim();
    const message =
      mutation.fieldError(name) ||
      (touched[name] && empty
        ? key === "dtl_cd"
          ? "코드를 입력하세요."
          : key === "cd_nm"
            ? "이름을 입력하세요."
            : "순서를 입력하세요."
        : undefined);
    return (
      <div data-testid={`admin-code-field-${index}-${key.replace(/_/g, "-")}`}>
        <input
          data-testid={`a-27-edit-${index}-${key.replace(/_/g, "-")}`}
          aria-label={`${row.dtl_cd || "신규"} ${key}`}
          name={name}
          type={type}
          value={value ?? ""}
          required={required}
          maxLength={maxLength}
          readOnly={readonly}
          disabled={mutation.pending}
          aria-invalid={!!message}
          className={styles.cellInput}
          onBlur={() => setTouched((old) => ({ ...old, [name]: true }))}
          onInvalid={(event) => {
            setTouched((old) => ({ ...old, [name]: true }));
            if (event.currentTarget === form.current?.querySelector(":invalid"))
              event.currentTarget.focus();
          }}
          onChange={(event) =>
            edit(index, {
              [key]:
                type === "number"
                  ? event.target.value === ""
                    ? undefined
                    : Number(event.target.value)
                  : event.target.value || (required ? "" : null),
            })
          }
        />
        {maxLength && !readonly && (
          <span className={styles.counter}>
            {String(value ?? "").length}/{maxLength}
          </span>
        )}
        {message && (
          <span role="alert" className={styles.fieldError}>
            {message}
          </span>
        )}
      </div>
    );
  }
  async function save() {
    const invalid = rows.flatMap((row, index) =>
      (["dtl_cd", "cd_nm", "sort_seq"] as const)
        .filter((key) => !String(row[key] ?? "").trim())
        .map((key) => `[${index}].${key}`),
    );
    if (invalid.length) {
      setTouched((old) => ({
        ...old,
        ...Object.fromEntries(invalid.map((name) => [name, true])),
      }));
      const first = form.current?.elements.namedItem(invalid[0]);
      if (first instanceof HTMLElement) first.focus();
      return;
    }
    if (!form.current?.reportValidity()) return;
    if (
      await mutation.run(
        () => saveGroupCodes(group.cd_grp_id, rows),
        form.current,
      )
    ) {
      onDirty(false);
      onSuccess("공통코드를 저장했습니다.");
      read.retry();
      onSaved();
    }
  }
  return (
    <section
      data-testid="admin-code-region"
      className={styles.panel}
      aria-label="관리 코드"
    >
      <h2>
        {group.cd_grp_nm} · {rows.length}개 {dirty ? "●" : ""}
      </h2>
      {system && <Notice>시스템 코드는 이름과 순서만 바꿀 수 있습니다.</Notice>}
      <ErrorBanner error={read.error} onRetry={read.retry} />
      <ErrorBanner error={mutation.error} onRetry={mutation.clear} />

      <form
        data-testid="a-27-save"
        ref={form}
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          void save();
        }}
      >
        <fieldset
          disabled={mutation.pending || read.loading || !!read.error}
          className={styles.fieldset}
        >
          <DataTable
            loading={read.loading}
            error={!!read.error}
            rows={rows.map((row, index) => ({ ...row, index }))}
            total={rows.length}
            dense={preferences.dense}
            rowKey={(row) => String(row.index)}
            columns={[
              {
                key: "dtl_cd",
                title: "코드",
                kind: "code",
                render: (row) =>
                  input(row.index, "dtl_cd", 30, row.index < original.length),
              },
              {
                key: "cd_nm",
                title: "이름 *",
                render: (row) => input(row.index, "cd_nm", 100),
              },
              ...([1, 2, 3] as const)
                .filter((n) => group[`attr${n}_nm`])
                .map((n) => ({
                  key: `attr${n}_val`,
                  title: group[`attr${n}_nm`]!,
                  render: (row: ManagedCode & { index: number }) =>
                    input(row.index, `attr${n}_val`, 100, system),
                })),
              {
                key: "sort_seq",
                title: "순서",
                kind: "number",
                render: (row) =>
                  input(row.index, "sort_seq", undefined, false, "number"),
              },
              {
                key: "aply_bgn_dt",
                title: "적용 시작일",
                kind: "date",
                render: (row) =>
                  input(row.index, "aply_bgn_dt", undefined, system, "date"),
              },
              {
                key: "use_yn",
                title: "사용",
                kind: "code",
                render: (row) => (
                  <Can perm="UI-27.delete">
                    <input
                      data-testid={`a-27-use-${row.index}`}
                      type="checkbox"
                      aria-label={`${row.dtl_cd} 사용`}
                      checked={row.use_yn === "Y"}
                      disabled={system || mutation.pending}
                      onChange={(event) =>
                        edit(row.index, {
                          use_yn: event.target.checked ? "Y" : "N",
                        })
                      }
                    />
                  </Can>
                ),
              },
            ]}
          />
          <div className={styles.toolbar}>
            {!system && (
              <Can perm="UI-27.create">
                <Button
                  onClick={() => {
                    setRows((old) => [
                      ...old,
                      {
                        dtl_cd: "",
                        cd_nm: "",
                        sort_seq:
                          Math.max(0, ...old.map((row) => row.sort_seq)) + 1,
                        use_yn: "Y",
                        attr1_val: null,
                        attr2_val: null,
                        attr3_val: null,
                        aply_bgn_dt: null,
                      },
                    ]);
                    onDirty(true);
                  }}
                >
                  코드 추가
                </Button>
              </Can>
            )}
            <Button
              disabled={!dirty}
              onClick={() => {
                setRows(original);
                setTouched({});
                onDirty(false);
                mutation.clear();
              }}
            >
              취소
            </Button>
            <Can perm="UI-27.update">
              <Button
                type="submit"
                primary
                pending={mutation.pending}
                disabled={!dirty}
              >
                저장
              </Button>
            </Can>
          </div>
        </fieldset>
      </form>
    </section>
  );
}
