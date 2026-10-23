import { useCallback, useState } from "react";
import { readData } from "../../shared/api";
import type { PageResult } from "../../shared/types";
import {
  DataTable,
  Pagination,
  SearchBar,
  Skeleton,
  type Column,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { formatDateTime } from "../../lib/format";
import { adminPaths, type CodeChange, type CodeGroup } from "./client";
import { useAdminRead } from "./useAdminResource";
import { useSharedPreferences } from "../shared/preferences";

export function HistoryTab({ groups }: { groups: CodeGroup[] }) {
  const [group, setGroup] = useState(""),
    [filter, setFilter] = useState(""),
    [page, setPage] = useState(1),
    [size, setSize] = useState<20 | 50 | 100>(
      () => useSharedPreferences.getState().pageSize,
    );
  const dense = useSharedPreferences((state) => state.dense),
    configure = useSharedPreferences((state) => state.configure);
  const read = useCallback(
      () =>
        readData<PageResult<CodeChange>>(
          `${adminPaths.history}?page=${page}&size=${size}${filter ? `&group=${encodeURIComponent(filter)}` : ""}`,
        ),
      [page, size, filter],
    ),
    resource = useAdminRead(read);
  const labels = {
    ADD: "추가",
    UPDATE: "수정",
    DISABLE: "사용 중지",
    ENABLE: "다시 사용",
  };
  const columns: Column<CodeChange>[] = [
    {
      key: "time",
      label: "일시",
      kind: "date",
      render: (r) => formatDateTime(r.chg_at),
    },
    {
      key: "group",
      label: "그룹",
      render: (r) => (r.cd_grp_id === "PARAM" ? "기준값" : r.cd_grp_nm),
    },
    {
      key: "code",
      label: "코드",
      kind: "code",
      render: (r) => r.dtl_cd ?? "–",
    },
    {
      key: "type",
      label: "구분",
      kind: "status",
      render: (r) => labels[r.cd_chg_ty_cd],
    },
    { key: "item", label: "항목", render: (r) => r.item_nm },
    { key: "before", label: "이전", render: (r) => r.bfr_val ?? "–" },
    { key: "after", label: "이후", render: (r) => r.aft_val ?? "–" },
    {
      key: "actor",
      label: "변경자",
      render: (r) =>
        r.chg_user_id === null ? "기본 사용자" : String(r.chg_user_id),
    },
  ];
  return (
    <section data-testid="history-tab">
      <h2>변경 이력</h2>
      <SearchBar
        onSearch={() => {
          setPage(1);
          if (filter === group) void resource.reload().catch(() => {});
          else setFilter(group);
        }}
        onReset={() => {
          setGroup("");
          setFilter("");
          setPage(1);
          if (!filter) void resource.reload().catch(() => {});
        }}
      >
        <label>
          그룹{" "}
          <select
            data-testid="history-group-select"
            aria-label="이력 그룹"
            value={group}
            onChange={(e) => setGroup(e.target.value)}
          >
            <option value="">전체</option>
            {groups.map((g) => (
              <option
                data-testid={`history-group-${g.cd_grp_id.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`}
                key={g.cd_grp_id}
                value={g.cd_grp_id}
              >
                {g.cd_grp_nm}
              </option>
            ))}
            <option value="PARAM">기준값</option>
          </select>
        </label>
      </SearchBar>
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
        rowKey={(r) => String(resource.data?.items.indexOf(r))}
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
    </section>
  );
}
