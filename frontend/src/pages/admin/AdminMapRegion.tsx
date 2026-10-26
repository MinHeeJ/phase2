import { useCallback, useEffect, useRef, useState } from "react";
import { useCodes, useSharedRead } from "../shared/sharedApi";
import { Actions, Button, Dialog } from "../../shared/ui";
import { TextInput, SelectInput, TextArea } from "../../shared/inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/permissions";
import { DataTable, Pagination } from "../../shared/table";
import { usePreferences } from "../../shared/preferences";
import {
  createCodeGroup,
  createExcelMap,
  listExcelMaps,
  updateExcelMap,
  type CodeGroup,
  type CodeGroupCreate,
  type ExcelMap,
  type ExcelMapCreate,
  type ExcelMapPatch,
} from "./adminApi";
import { useAdminMutation } from "./adminForms";
import type { AdminRegionProps } from "./UI27";
import styles from "./admin.module.css";

export function AdminGroupDialog({
  onClose,
  onSaved,
  onDirty,
}: {
  onClose: () => void;
  onSaved: () => void;
  onDirty: (value: boolean) => void;
}) {
  const [draft, setDraft] = useState<CodeGroupCreate>({
    cd_grp_id: "",
    cd_grp_nm: "",
  });
  const mutation = useAdminMutation();
  const form = useRef<HTMLFormElement>(null);
  const fields = [
    ["cd_grp_id", "그룹 코드", 30],
    ["cd_grp_nm", "그룹명", 100],
    ["attr1_nm", "추가 값1 이름", 50],
    ["attr2_nm", "추가 값2 이름", 50],
    ["attr3_nm", "추가 값3 이름", 50],
  ] as const;
  return (
    <Dialog
      title="그룹 추가"
      onClose={() => {
        if (!mutation.pending) onClose();
      }}
    >
      <ErrorBanner error={mutation.error} onRetry={mutation.clear} />
      <form
        data-testid="admin-group-create-form"
        ref={form}
        className={styles.form}
        onSubmit={async (event) => {
          event.preventDefault();
          if (await mutation.run(() => createCodeGroup(draft), form.current))
            onSaved();
        }}
      >
        <fieldset className={styles.fieldset} disabled={mutation.pending}>
          {fields.map(([key, label, max]) => (
            <TextInput
              key={key}
              name={key}
              label={label}
              required={key === "cd_grp_id" || key === "cd_grp_nm"}
              maxLength={max}
              pattern={key === "cd_grp_id" ? "[A-Z_]{1,30}" : undefined}
              value={draft[key] ?? ""}
              error={mutation.fieldError(key)}
              onChange={(event) => {
                setDraft({ ...draft, [key]: event.target.value });
                onDirty(true);
              }}
            />
          ))}
        </fieldset>
        <Actions>
          <Button disabled={mutation.pending} onClick={onClose}>
            취소
          </Button>
          <Can perm="UI-27.create">
            <Button primary type="submit" pending={mutation.pending}>
              저장
            </Button>
          </Can>
        </Actions>
      </form>
    </Dialog>
  );
}

function AdminMapDialog({
  groups,
  onClose,
  onSaved,
  onDirty,
}: {
  groups: CodeGroup[];
  onClose: () => void;
  onSaved: () => void;
  onDirty: (value: boolean) => void;
}) {
  const [draft, setDraft] = useState<
    Omit<ExcelMapCreate, "map_role_cd"> & { map_role_cd: "" | "MAIN" | "SUB" }
  >({
    excel_lbl_nm: "",
    trns_cd_grp_id: "",
    trns_cd: "",
    map_role_cd: "",
    rmrk_desc: "",
  });
  const options = useCodes(draft.trns_cd_grp_id || null);
  const mutation = useAdminMutation();
  const form = useRef<HTMLFormElement>(null);
  const update = (patch: Partial<ExcelMapCreate>) => {
    setDraft((old) => ({ ...old, ...patch }));
    onDirty(true);
  };
  return (
    <Dialog
      title="대응 추가"
      onClose={() => {
        if (!mutation.pending) onClose();
      }}
    >
      <ErrorBanner error={mutation.error} onRetry={mutation.clear} />
      <ErrorBanner error={options.error} onRetry={options.retry} />
      <form
        data-testid="admin-map-create-form"
        ref={form}
        className={styles.form}
        onSubmit={async (event) => {
          event.preventDefault();
          if (!draft.map_role_cd) return;
          if (
            await mutation.run(
              () =>
                createExcelMap({
                  ...draft,
                  map_role_cd: draft.map_role_cd as "MAIN" | "SUB",
                }),
              form.current,
            )
          )
            onSaved();
        }}
      >
        <fieldset disabled={mutation.pending} className={styles.fieldset}>
          <TextInput
            name="excel_lbl_nm"
            label="엑셀 표기"
            required
            maxLength={100}
            value={draft.excel_lbl_nm}
            error={mutation.fieldError("excel_lbl_nm")}
            onChange={(event) => update({ excel_lbl_nm: event.target.value })}
          />
          <SelectInput
            name="trns_cd_grp_id"
            label="그룹"
            required
            value={draft.trns_cd_grp_id}
            error={mutation.fieldError("trns_cd_grp_id")}
            onChange={(event) =>
              update({ trns_cd_grp_id: event.target.value, trns_cd: "" })
            }
          >
            <option value="">선택하세요</option>
            {groups
              .filter((group) => group.use_yn === "Y")
              .map((group) => (
                <option
                  data-testid={`admin-map-group-option-${groups.indexOf(group)}`}
                  key={group.cd_grp_id}
                  value={group.cd_grp_id}
                >
                  {group.cd_grp_nm}
                </option>
              ))}
          </SelectInput>
          <SelectInput
            name="trns_cd"
            label="대응 코드"
            required
            value={draft.trns_cd}
            disabled={
              !draft.trns_cd_grp_id || options.loading || !!options.error
            }
            error={mutation.fieldError("trns_cd")}
            onChange={(event) => update({ trns_cd: event.target.value })}
          >
            <option value="">선택하세요</option>
            {options.data?.map((row, index) => (
              <option
                data-testid={`admin-map-code-option-${index}`}
                key={row.code}
                value={row.code}
              >
                {row.name}
              </option>
            ))}
          </SelectInput>
          <SelectInput
            name="map_role_cd"
            label="주/보조"
            required
            value={draft.map_role_cd}
            error={mutation.fieldError("map_role_cd")}
            onChange={(event) =>
              update({ map_role_cd: event.target.value as "MAIN" | "SUB" })
            }
          >
            <option value="">선택하세요</option>
            <option value="MAIN">주</option>
            <option value="SUB">보조</option>
          </SelectInput>
          <TextArea
            name="rmrk_desc"
            label="메모"
            maxLength={200}
            value={draft.rmrk_desc}
            error={mutation.fieldError("rmrk_desc")}
            onChange={(event) => update({ rmrk_desc: event.target.value })}
          />
        </fieldset>
        <Actions>
          <Button disabled={mutation.pending} onClick={onClose}>
            취소
          </Button>
          <Can perm="UI-27.create">
            <Button
              type="submit"
              primary
              pending={mutation.pending}
              disabled={
                options.loading ||
                !!options.error ||
                !draft.trns_cd ||
                !draft.map_role_cd
              }
            >
              저장
            </Button>
          </Can>
        </Actions>
      </form>
    </Dialog>
  );
}

export function AdminMapRegion({
  groups,
  onDirty,
  onSuccess,
  guard,
}: AdminRegionProps & { groups: CodeGroup[] }) {
  const preferences = usePreferences();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState<20 | 50 | 100>(preferences.pageSize);
  const load = useCallback(() => listExcelMaps(page, size), [page, size]);
  const read = useSharedRead(load);
  const [editing, setEditing] = useState<ExcelMap | null>(null);
  const [draft, setDraft] = useState<ExcelMapPatch>({});
  const [adding, setAdding] = useState(false);
  const mutation = useAdminMutation();
  const form = useRef<HTMLFormElement>(null);
  useEffect(() => {
    onDirty(!!editing || adding);
    return () => onDirty(false);
  }, [editing, adding, onDirty]);
  function close() {
    setEditing(null);
    setDraft({});
    mutation.clear();
    onDirty(false);
  }
  async function save() {
    if (!editing) return;
    const changes: ExcelMapPatch = {};
    if (
      draft.map_role_cd !== undefined &&
      draft.map_role_cd !== editing.map_role_cd
    )
      changes.map_role_cd = draft.map_role_cd;
    if (draft.use_yn !== undefined && draft.use_yn !== editing.use_yn)
      changes.use_yn = draft.use_yn;
    if (
      draft.rmrk_desc !== undefined &&
      draft.rmrk_desc !== (editing.rmrk_desc ?? "")
    )
      changes.rmrk_desc = draft.rmrk_desc;
    if (!Object.keys(changes).length) {
      close();
      return;
    }
    if (
      await mutation.run(
        () => updateExcelMap(editing.map_id, changes),
        form.current,
      )
    ) {
      close();
      read.retry();
      onSuccess("대응을 저장했습니다.");
    }
  }
  return (
    <section
      data-testid="admin-map-region"
      className={styles.panel}
      aria-label="엑셀 표기 대응"
    >
      <div className={styles.regionHeading}>
        <div>
          <h2>엑셀 표기 대응</h2>
          <p>엑셀 표기와 실제 코드의 대응 관계를 관리합니다.</p>
        </div>
        <Can perm="UI-27.create">
          <Button
            onClick={() =>
              guard(() => {
                close();
                setAdding(true);
              })
            }
          >
            대응 추가
          </Button>
        </Can>
      </div>
      <ErrorBanner error={read.error} onRetry={read.retry} />
      <ErrorBanner error={mutation.error} onRetry={mutation.clear} />
      <DataTable
        loading={read.loading}
        error={!!read.error}
        rows={read.data?.items ?? []}
        total={read.data?.total ?? 0}
        dense={preferences.dense}
        rowKey={(row) => row.map_id}
        columns={[
          {
            key: "label",
            title: "엑셀 표기",
            render: (row) => row.excel_lbl_nm,
          },
          {
            key: "target",
            title: "바꾸는 값",
            render: (row) => `${row.cd_grp_nm} · ${row.cd_nm}`,
          },
          {
            key: "role",
            title: "주/보조",
            render: (row) => (row.map_role_cd === "MAIN" ? "주" : "보조"),
          },
          { key: "memo", title: "메모", render: (row) => row.rmrk_desc ?? "–" },
          {
            key: "use",
            title: "사용",
            render: (row) => (row.use_yn === "Y" ? "사용" : "사용 안 함"),
          },
          {
            key: "edit",
            title: "동작",
            render: (row) => (
              <Can perm="UI-27.update">
                <Button
                  onClick={() =>
                    guard(() => {
                      setEditing(row);
                      setDraft({
                        map_role_cd: row.map_role_cd,
                        rmrk_desc: row.rmrk_desc ?? "",
                        use_yn: row.use_yn,
                      });
                    })
                  }
                >
                  편집
                </Button>
              </Can>
            ),
          },
        ]}
      />
      <Pagination
        page={page}
        size={size}
        total={read.data?.total ?? 0}
        onPage={(value) =>
          guard(() => {
            close();
            setPage(value);
          })
        }
        onSize={(value) =>
          guard(() => {
            close();
            setPage(0);
            setSize(value);
          })
        }
      />
      {editing && (
        <form
          data-testid="admin-map-edit-form"
          ref={form}
          aria-label="대응 편집"
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault();
            void save();
          }}
        >
          <p>
            {editing.excel_lbl_nm} → {editing.cd_grp_nm} · {editing.cd_nm} (변경
            불가)
          </p>
          <fieldset disabled={mutation.pending} className={styles.fieldset}>
            <SelectInput
              name="map_role_cd"
              label="주/보조"
              value={draft.map_role_cd}
              error={mutation.fieldError("map_role_cd")}
              onChange={(event) =>
                setDraft({
                  ...draft,
                  map_role_cd: event.target.value as "MAIN" | "SUB",
                })
              }
            >
              <option value="MAIN">주</option>
              <option value="SUB">보조</option>
            </SelectInput>
            <TextArea
              name="rmrk_desc"
              label="메모"
              maxLength={200}
              value={draft.rmrk_desc}
              error={mutation.fieldError("rmrk_desc")}
              onChange={(event) =>
                setDraft({ ...draft, rmrk_desc: event.target.value })
              }
            />
            <Can perm="UI-27.delete">
              <label>
                <input
                  data-testid="admin-map-edit-use"
                  name="use_yn"
                  type="checkbox"
                  aria-invalid={!!mutation.fieldError("use_yn")}
                  aria-describedby={
                    mutation.fieldError("use_yn")
                      ? "admin-map-use-error"
                      : undefined
                  }
                  checked={draft.use_yn === "Y"}
                  onChange={(event) =>
                    setDraft({
                      ...draft,
                      use_yn: event.target.checked ? "Y" : "N",
                    })
                  }
                />
                사용
              </label>
              {mutation.fieldError("use_yn") && (
                <span id="admin-map-use-error" className={styles.fieldError}>
                  {mutation.fieldError("use_yn")}
                </span>
              )}
            </Can>
          </fieldset>
          <Actions>
            <Button disabled={mutation.pending} onClick={() => guard(close)}>
              취소
            </Button>
            <Can perm="UI-27.update">
              <Button primary type="submit" pending={mutation.pending}>
                저장
              </Button>
            </Can>
          </Actions>
        </form>
      )}
      {adding && (
        <AdminMapDialog
          groups={groups}
          onDirty={onDirty}
          onClose={() =>
            guard(() => {
              setAdding(false);
              onDirty(false);
            })
          }
          onSaved={() => {
            setAdding(false);
            onDirty(false);
            read.retry();
            onSuccess("대응을 추가했습니다.");
          }}
        />
      )}
    </section>
  );
}
