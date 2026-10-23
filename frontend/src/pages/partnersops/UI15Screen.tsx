import { useEffect, useState } from "react";
import { ScreenHeading } from "../shared/ScreenHeading";
import { useCodes } from "../shared/useCodes";
import { Button, Pagination, SearchBar, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { readData, type ApiFailure } from "../../shared/api";
import type { PageResult } from "../../shared/types";
import { PartnerStatusActions, PartnerExportButton } from "./PartnerActions";
import { PartnerActivity } from "./PartnerActivity";
import {
  partnerColumns,
  partnerFailure,
  type PartnerSelection,
  type PartnerFilters,
} from "./client";
import styles from "./Partners.module.css";

type PartnerRow = PartnerSelection & Record<string, string | number | null>;
// SCR-UI-15. The owning partners page can also reuse PartnerActivity/PartnerStatusActions/PartnerExportButton directly.
export default function UI15Screen({
  name = "거래처·영업 파트",
  groupName,
}: {
  name?: string;
  groupName?: string;
}) {
  const kinds = useCodes("PARTNER_KIND");
  const [filters, setFilters] = useState<PartnerFilters>({ useYn: "Y" }),
    [draft, setDraft] = useState<PartnerFilters>({ useYn: "Y" }),
    [page, setPage] = useState(1),
    [size, setSize] = useState<20 | 50 | 100>(50),
    [data, setData] = useState<PageResult<PartnerRow> | null>(null),
    [selected, setSelected] = useState<PartnerRow[]>([]),
    [detail, setDetail] = useState<PartnerRow | null>(null),
    [detailTab, setDetailTab] = useState<"basic" | "assignments" | "history">(
      "basic",
    ),
    [loading, setLoading] = useState(true),
    [error, setError] = useState<ApiFailure | null>(null),
    [revision, setRevision] = useState(0);
  const reload = () => setRevision((r) => r + 1);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    const query = new URLSearchParams({
      page: String(page),
      size: String(size),
    });
    Object.entries(filters).forEach(([key, value]) => {
      if (value !== undefined) query.set(key, value);
    });
    readData<PageResult<PartnerRow>>(
      `/api/partners?${query}`,
      controller.signal,
    )
      .then((value) => {
        if (!controller.signal.aborted) {
          setData(value);
          setSelected([]);
        }
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(partnerFailure(e));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [page, size, filters, revision]);
  return (
    <section
      data-testid="partner-screen"
      data-screen-id="SCR-UI-15"
      className={styles.region}
    >
      <ScreenHeading screenId="UI-15" name={name} groupName={groupName} />
      <SearchBar
        onSearch={() => {
          setPage(1);
          setFilters(draft);
        }}
        onReset={() => {
          setPage(1);
          setDraft({ useYn: "Y" });
          setFilters({ useYn: "Y" });
        }}
      >
        <label>
          구분
          <select
            data-testid="partner-kind-filter"
            value={draft.kind ?? ""}
            onChange={(e) =>
              setDraft({ ...draft, kind: e.target.value || undefined })
            }
          >
            <option value="">전체</option>
            {kinds.codes.map((kind) => (
              <option value={kind.code} key={kind.code}>
                {kind.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          사용 여부
          <select
            data-testid="partner-use-filter"
            value={draft.useYn}
            onChange={(e) => setDraft({ ...draft, useYn: e.target.value })}
          >
            <option value="">전체</option>
            <option value="Y">사용</option>
            <option value="N">중지</option>
          </select>
        </label>
        <label>
          상호
          <input
            data-testid="partner-query-filter"
            placeholder="상호 또는 사업자번호"
            value={draft.q ?? ""}
            onChange={(e) => setDraft({ ...draft, q: e.target.value })}
          />
        </label>
      </SearchBar>
      <ErrorBanner error={kinds.error} onRetry={kinds.retry} />
      <ErrorBanner error={error} onRetry={reload} />
      <div className={styles.toolbar}>
        <span>총 {data?.total ?? "–"}건</span>
        <PartnerStatusActions rows={selected} onChanged={reload} />
        <PartnerExportButton
          filters={filters}
          columns={partnerColumns.map((c) => c.key)}
          ids={selected.map((row) => row.ptnr_id)}
          total={data?.total ?? 0}
        />
      </div>
      {loading && <Skeleton />}
      {!loading && !error && (
        <div className={styles.tableScroll}>
          <table>
            <thead>
              <tr>
                <th>선택</th>
                {partnerColumns.map((c) => (
                  <th key={c.key}>
                    <Button
                      data-testid={`partner-sort-${c.key}`}
                      variant="text"
                      onClick={() =>
                        setFilters({
                          ...filters,
                          sort: `${c.key},${filters.sort === `${c.key},asc` ? "desc" : "asc"}`,
                        })
                      }
                    >
                      {c.label}
                    </Button>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {!data?.items.length && (
                <tr>
                  <td colSpan={partnerColumns.length + 1}>
                    조건에 맞는 결과가 없습니다
                  </td>
                </tr>
              )}
              {data?.items.map((row) => (
                <tr
                  data-testid={`partner-row-${row.ptnr_id}`}
                  key={row.ptnr_id}
                >
                  <td>
                    <input
                      data-testid={`partner-select-${row.ptnr_id}`}
                      type="checkbox"
                      aria-label={`${row.ptnr_nm} 선택`}
                      checked={selected.some((r) => r.ptnr_id === row.ptnr_id)}
                      onChange={(e) =>
                        setSelected(
                          e.target.checked
                            ? [...selected, row]
                            : selected.filter((r) => r.ptnr_id !== row.ptnr_id),
                        )
                      }
                    />
                  </td>
                  {partnerColumns.map((c) => (
                    <td key={c.key}>
                      {c.key === "ptnr_nm" ? (
                        <Button
                          data-testid={`partner-detail-${row.ptnr_id}`}
                          variant="text"
                          onClick={() => {
                            setDetail(row);
                            setDetailTab("basic");
                          }}
                        >
                          {row.ptnr_nm}
                        </Button>
                      ) : (
                        (row[c.key] ?? "–")
                      )}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Pagination
        page={page}
        size={size}
        total={data?.total ?? 0}
        onPage={setPage}
        onSize={(value) => {
          setPage(1);
          setSize(value);
        }}
      />
      {detail && (
        <aside
          data-testid="partner-detail"
          className={styles.drawer}
          aria-label="거래처 상세"
        >
          <h2>{detail.ptnr_nm}</h2>
          <p>
            {detail.kindName} · {detail.biz_reg_no}
          </p>
          <Button
            data-testid="partner-detail-close"
            onClick={() => setDetail(null)}
          >
            닫기
          </Button>
          <PartnerStatusActions
            rows={[detail]}
            onChanged={async () => {
              setDetail(
                await readData<PartnerRow>(
                  `/api/partners/${encodeURIComponent(detail.ptnr_id)}`,
                ),
              );
              reload();
            }}
          />
          <div role="tablist">
            <Button
              data-testid="partner-basic-tab"
              role="tab"
              aria-selected={detailTab === "basic"}
              onClick={() => setDetailTab("basic")}
            >
              기본 정보
            </Button>
            <Button
              data-testid="partner-trades-tab"
              role="tab"
              aria-selected={detailTab === "assignments"}
              onClick={() => setDetailTab("assignments")}
            >
              거래 내역
            </Button>
            <Button
              data-testid="partner-changes-tab"
              role="tab"
              aria-selected={detailTab === "history"}
              onClick={() => setDetailTab("history")}
            >
              변경 이력 보기
            </Button>
          </div>
          {detailTab === "basic" ? (
            <dl data-testid="partner-basic-panel" role="tabpanel">
              {partnerColumns.map((c) => (
                <div key={c.key}>
                  <dt>{c.label}</dt>
                  <dd>{detail[c.key] ?? "–"}</dd>
                </div>
              ))}
            </dl>
          ) : (
            <PartnerActivity
              key={`${detail.ptnr_id}-${detailTab}`}
              id={detail.ptnr_id}
              initialTab={detailTab}
            />
          )}
        </aside>
      )}
    </section>
  );
}
