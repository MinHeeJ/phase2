import { useCallback, useState } from "react";
import { useSharedRead } from "../shared/sharedApi";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { SearchForm } from "../../shared/ui";
import { SelectInput } from "../../shared/inputs";
import { DataTable, Pagination } from "../../shared/table";
import { usePreferences } from "../../shared/preferences";
import { formatDateTime } from "../../lib/format";
import { listCodeHistory, type CodeGroup } from "./adminApi";
import styles from "./admin.module.css";

const labels = {
  ADD: "추가",
  UPDATE: "수정",
  DISABLE: "사용 중지",
  ENABLE: "다시 사용",
};
export function AdminHistoryRegion({ groups }: { groups: CodeGroup[] }) {
  const preferences = usePreferences();
  const [filter, setFilter] = useState("");
  const [group, setGroup] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState<20 | 50 | 100>(50);
  const load = useCallback(
    () => listCodeHistory(group || undefined, page, size),
    [group, page, size],
  );
  const read = useSharedRead(load);
  return (
    <section
      data-testid="admin-history-region"
      aria-label="변경 이력"
      className={styles.panel}
    >
      <div className={styles.regionHeading}>
        <div>
          <h2>변경 이력</h2>
          <p>그룹별 변경 내역을 최신순으로 조회합니다.</p>
        </div>
      </div>
      <SearchForm
        onSearch={() => {
          setGroup(filter);
          setPage(0);
          read.retry();
        }}
        onReset={() => {
          setFilter("");
        }}
      >
        <SelectInput
          label="그룹"
          name="group"
          value={filter}
          onChange={(event) => setFilter(event.target.value)}
        >
          <option value="">전체</option>
          <option value="PARAM">기준값</option>
          {groups
            .filter((row) => row.cd_grp_id !== "PARAM")
            .map((row) => (
              <option
                data-testid={`admin-history-group-option-${groups.indexOf(row)}`}
                key={row.cd_grp_id}
                value={row.cd_grp_id}
              >
                {row.cd_grp_nm}
              </option>
            ))}
        </SelectInput>
      </SearchForm>
      <ErrorBanner error={read.error} onRetry={read.retry} />
      <DataTable
        loading={read.loading}
        error={!!read.error}
        rows={read.data?.items ?? []}
        total={read.data?.total ?? 0}
        dense={preferences.dense}
        rowKey={(row) =>
          `${row.chg_at}-${row.cd_grp_id}-${row.dtl_cd}-${row.item_nm}-${row.aft_val}`
        }
        columns={[
          {
            key: "at",
            title: "일시",
            kind: "date",
            render: (row) => formatDateTime(row.chg_at),
          },
          {
            key: "group",
            title: "그룹",
            render: (row) =>
              row.cd_grp_id === "PARAM" ? "기준값" : row.cd_grp_nm,
          },
          {
            key: "code",
            title: "코드",
            kind: "code",
            render: (row) => row.dtl_cd ?? "–",
          },
          {
            key: "type",
            title: "변경 구분",
            kind: "status",
            render: (row) => labels[row.cd_chg_ty_cd],
          },
          { key: "item", title: "항목", render: (row) => row.item_nm },
          {
            key: "before",
            title: "이전 값",
            render: (row) => row.bfr_val ?? "–",
          },
          {
            key: "after",
            title: "이후 값",
            render: (row) => row.aft_val ?? "–",
          },
          {
            key: "actor",
            title: "변경자",
            render: (row) =>
              row.chg_user_id == null ? "기본 사용자" : String(row.chg_user_id),
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
    </section>
  );
}
