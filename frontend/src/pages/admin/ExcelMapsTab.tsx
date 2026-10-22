import { useCallback, useEffect, useState } from "react";
import { readData } from "../../shared/api";
import type { PageResult } from "../../shared/types";
import {
  DataTable,
  Pagination,
  Button,
  Skeleton,
  type Column,
} from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import {
  adminPaths,
  updateExcelMap,
  type ExcelMap,
  type CodeGroup,
} from "./client";
import { ExcelMapDialog } from "./CreateDialogs";
import { useAdminRead, useAdminWrite } from "./useAdminResource";
import { useAdminGuard } from "./AdminDialogs";
import { useSharedPreferences } from "../shared/preferences";
import controls from "../../shared/Controls.module.css";
import styles from "./CommonCodes.module.css";

function MappingRow({
  row,
  reload,
  onDirtyChange,
}: {
  row: ExcelMap;
  reload: () => Promise<unknown>;
  onDirtyChange: (id: string, dirty: boolean) => void;
}) {
  const [draft, setDraft] = useState(row),
    write = useAdminWrite(reload);
  const [edited, setEdited] = useState(false);
  const dirty =
    edited &&
    (draft.map_role_cd !== row.map_role_cd ||
      draft.rmrk_desc !== row.rmrk_desc ||
      draft.use_yn !== row.use_yn);
  useEffect(() => {
    onDirtyChange(row.map_id, dirty);
    return () => onDirtyChange(row.map_id, false);
  }, [dirty, row.map_id, onDirtyChange]);
  useEffect(() => {
    if (!dirty) setDraft(row);
  }, [row, dirty]);
  const change = (key: string, value: string) => {
    write.clear();
    setEdited(true);
    setDraft((d) => ({ ...d, [key]: value }));
  };
  const field = (key: string) =>
    write.error?.field === key ? write.error.message : null;
  return (
    <tr>
      <td>{row.excel_lbl_nm}</td>
      <td>
        {row.cd_grp_nm} · {row.cd_nm}
      </td>
      <td>
        <select
          data-testid={`excel-map-role-${row.map_id}`}
          aria-label={`주/보조 ${row.map_id}`}
          value={draft.map_role_cd}
          disabled={write.pending}
          onChange={(e) => change("map_role_cd", e.target.value)}
        >
          <option value="MAIN">주</option>
          <option value="SUB">보조</option>
        </select>
      </td>
      <td>
        <input
          data-testid={`excel-map-memo-${row.map_id}`}
          aria-label={`메모 ${row.map_id}`}
          name="rmrk_desc"
          value={draft.rmrk_desc ?? ""}
          maxLength={200}
          disabled={write.pending}
          aria-invalid={!!field("rmrk_desc")}
          onChange={(e) => change("rmrk_desc", e.target.value)}
        />
        {field("rmrk_desc") && <span role="alert">{field("rmrk_desc")}</span>}
      </td>
      <td>
        <Can perm="UI-27.delete">
          <input
            data-testid={`excel-map-use-${row.map_id}`}
            aria-label={`사용 ${row.map_id}`}
            type="checkbox"
            checked={draft.use_yn === "Y"}
            disabled={write.pending}
            onChange={(e) => change("use_yn", e.target.checked ? "Y" : "N")}
          />
        </Can>
      </td>
      <td>
        <Can perm="UI-27.update">
          <Button
            pending={write.pending}
            disabled={!dirty || write.uncertain}
            onClick={() => {
              void write.save(
                () =>
                  updateExcelMap(row.map_id, {
                    map_role_cd: draft.map_role_cd,
                    rmrk_desc: draft.rmrk_desc ?? "",
                    use_yn: draft.use_yn,
                  }),
                () => {
                  setEdited(false);
                  onDirtyChange(row.map_id, false);
                },
              );
            }}
          >
            저장
          </Button>
        </Can>
        <Button
          disabled={write.pending}
          onClick={() => {
            setEdited(false);
            setDraft(row);
            write.clear();
          }}
        >
          취소
        </Button>
        <ErrorBanner
          error={write.error}
          onRetry={() => {
            void write.recover();
          }}
        />
      </td>
    </tr>
  );
}
export function ExcelMapsTab({
  groups,
  onDirtyChange,
}: {
  groups: CodeGroup[];
  onDirtyChange: (dirty: boolean) => void;
}) {
  const [page, setPage] = useState(1),
    [size, setSize] = useState<20 | 50 | 100>(
      () => useSharedPreferences.getState().pageSize,
    ),
    [dialog, setDialog] = useState(false),
    [dirtyRows, setDirtyRows] = useState<Record<string, boolean>>({});
  const dense = useSharedPreferences((state) => state.dense),
    configure = useSharedPreferences((state) => state.configure);
  const [dialogDirty, setDialogDirty] = useState(false);
  const read = useCallback(
      () =>
        readData<PageResult<ExcelMap>>(
          `${adminPaths.maps}?page=${page}&size=${size}`,
        ),
      [page, size],
    ),
    resource = useAdminRead(read);
  const rowDirty = useCallback(
    (id: string, dirty: boolean) =>
      setDirtyRows((rows) =>
        rows[id] === dirty ? rows : { ...rows, [id]: dirty },
      ),
    [],
  );
  const guard = useAdminGuard(Object.values(dirtyRows).some(Boolean));
  const requestLeave = guard.request;
  useEffect(() => {
    onDirtyChange(dialogDirty || Object.values(dirtyRows).some(Boolean));
  }, [dirtyRows, dialogDirty, onDirtyChange]);
  const rows = resource.data?.items ?? [];
  return (
    <section data-testid="excel-maps-tab" className={styles.region}>
      <h2>엑셀 표기 대응</h2>
      <Can perm="UI-27.create">
        <Button onClick={() => setDialog(true)}>대응 추가</Button>
      </Can>
      <ErrorBanner
        error={resource.error}
        onRetry={() => {
          void resource.reload().catch(() => {});
        }}
      />
      {resource.loading && <Skeleton />}
      {!resource.loading && !resource.error && !rows.length && (
        <p>조건에 맞는 결과가 없습니다</p>
      )}
      <div className={styles.tableScroll}>
        <table className={dense ? controls.dense : ""}>
          <caption>
            총 {resource.data?.total ?? 0}건{" "}
            <label>
              <input
                data-testid="excel-maps-dense-toggle"
                type="checkbox"
                checked={dense}
                onChange={(e) => configure({ dense: e.target.checked })}
              />
              좁게 보기
            </label>
          </caption>
          <thead>
            <tr>
              {[
                "엑셀 표기",
                "바꾸는 값",
                "주/보조",
                "메모",
                "사용",
                "동작",
              ].map((t) => (
                <th
                  data-testid={`excel-maps-column-${["엑셀 표기", "바꾸는 값", "주/보조", "메모", "사용", "동작"].indexOf(t)}`}
                  key={t}
                >
                  {t}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <MappingRow
                key={row.map_id}
                row={row}
                reload={resource.reload}
                onDirtyChange={rowDirty}
              />
            ))}
          </tbody>
        </table>
      </div>
      <Pagination
        page={page}
        size={size}
        total={resource.data?.total ?? 0}
        onPage={(p) => requestLeave(() => setPage(p))}
        onSize={(s) =>
          requestLeave(() => {
            setSize(s);
            setPage(1);
          })
        }
      />
      {dialog && (
        <ExcelMapDialog
          groups={groups}
          onDirtyChange={setDialogDirty}
          reload={resource.reload}
          onCancel={() => setDialog(false)}
          onSaved={() => setDialog(false)}
        />
      )}
      {guard.confirmation}
    </section>
  );
}
