import { useCallback, useEffect, useState, type ReactNode } from "react";
import { Badge, Button, Pagination, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import type { ApiFailure } from "../../shared/api";
import {
  listSalesParts,
  type SalesPartPage,
  type SalesPartDetail,
} from "./client";
import { SalesPartCreateDialog, failure } from "./SalesPartCreateDialog";
import { SalesPartDetailDrawer } from "./SalesPartDetailDrawer";
import styles from "./SalesParts.module.css";

const columns = [
  ["bsn_part_nm", "파트명"],
  ["ldr_nm", "파트장"],
  ["prft_dstrb_rt", "이익 배분율(%)"],
  ["bsn_fee_amt", "영업비(1인·월, 원)"],
  ["aply_bgn_ym", "적용 시작"],
  ["scheduledChanges", "예정된 변경"],
  ["peopleCount", "소속 인력"],
  ["projectCount", "진행 프로젝트"],
  ["use_yn", "사용 여부"],
] as const;

// SCR-UI-15: 영업 파트 탭. The shared UI-15 host supplies partner navigation and operation actions.
export default function UI15Screen({
  onPartners,
  renderActions,
  renderDownload,
  onDirtyChange,
}: {
  onPartners?: () => void;
  renderActions?: (
    part: SalesPartDetail,
    onDetailChange: (part: SalesPartDetail) => void,
    onChanged: () => Promise<void>,
    onDirtyChange: (dirty: boolean) => void,
  ) => ReactNode;
  renderDownload?: (
    columns: string[],
    selectedIds: string[],
    total: number,
  ) => ReactNode;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [page, setPage] = useState(1),
    [size, setSize] = useState<20 | 50 | 100>(50),
    [sort, setSort] = useState("");
  const [data, setData] = useState<SalesPartPage | null>(null),
    [error, setError] = useState<ApiFailure | null>(null);
  const [loading, setLoading] = useState(true),
    [generation, setGeneration] = useState(0);
  const [selected, setSelected] = useState<string | null>(null),
    [create, setCreate] = useState(false),
    [success, setSuccess] = useState("");
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const reload = async () => {
    setSelectedIds([]);
    setGeneration((n) => n + 1);
  };
  const close = useCallback(() => setSelected(null), []);
  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    setSelectedIds([]);
    void listSalesParts(page, size, sort)
      .then((result) => {
        if (alive) setData(result);
      })
      .catch((e: unknown) => {
        if (alive) setError(failure(e));
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [page, size, sort, generation]);
  return (
    <section
      data-testid="sales-parts-screen"
      data-screen-id="SCR-UI-15"
      aria-label="영업 파트"
    >
      <header className={styles.toolbar}>
        <h1>거래처·영업 파트</h1>
        <span className={styles.writeActions}>
          <Button
            data-testid="sales-part-add"
            variant="primary"
            disabled={!data || loading || !!error}
            onClick={() => setCreate(true)}
          >
            파트 추가
          </Button>
        </span>
      </header>
      <div role="tablist" className={styles.tabs}>
        {onPartners && (
          <button
            data-testid="sales-parts-partners-tab"
            type="button"
            role="tab"
            aria-selected="false"
            onClick={onPartners}
          >
            거래처
          </button>
        )}
        <button
          data-testid="sales-parts-tab"
          type="button"
          role="tab"
          aria-selected="true"
        >
          영업 파트
        </button>
      </div>
      <p className={styles.notice}>
        이익 배분율·영업비를 바꾸면 적용 시작월부터 새 기준을 씁니다. 지난 달
        정산은 바뀌지 않습니다. 기준 변경은 시스템 관리자·재무 담당만 할 수
        있습니다.
      </p>
      {success && <p role="status">{success}</p>}
      <ErrorBanner error={error} onRetry={() => setGeneration((n) => n + 1)} />
      {loading && <Skeleton />}
      {!loading && !error && data && (
        <>
          <div className={styles.toolbar}>
            <p>총 {data.total.toLocaleString("ko-KR")}건</p>
            {renderDownload?.(
              columns.map(([key]) => key),
              selectedIds,
              data.total,
            )}
          </div>
          <div className={styles.tableScroll}>
            <table>
              <thead>
                <tr>
                  {renderDownload && (
                    <th className={styles.writeActions}>선택</th>
                  )}
                  {columns.map(([key, label]) => (
                    <th
                      key={key}
                      aria-sort={
                        sort.startsWith(`${key},`)
                          ? sort.endsWith("asc")
                            ? "ascending"
                            : "descending"
                          : undefined
                      }
                    >
                      {key === "scheduledChanges" ? (
                        label
                      ) : (
                        <button
                          data-testid={`sales-parts-sort-${key.replaceAll("_", "-").replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`)}`}
                          type="button"
                          onClick={() => {
                            setPage(1);
                            setSort(
                              `${key},${sort === `${key},asc` ? "desc" : "asc"}`,
                            );
                          }}
                        >
                          {label}
                        </button>
                      )}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.items.map((p) => (
                  <tr
                    key={p.bsn_part_id}
                    data-testid={`sales-part-row-${p.bsn_part_id}`}
                  >
                    {renderDownload && (
                      <td className={styles.writeActions}>
                        <input
                          data-testid={`sales-part-select-${p.bsn_part_id}`}
                          type="checkbox"
                          aria-label={`${p.bsn_part_nm} 선택`}
                          checked={selectedIds.includes(p.bsn_part_id)}
                          onChange={(event) =>
                            setSelectedIds((ids) =>
                              event.target.checked
                                ? [...ids, p.bsn_part_id]
                                : ids.filter((id) => id !== p.bsn_part_id),
                            )
                          }
                        />
                      </td>
                    )}
                    <td>
                      <Button
                        data-testid={`sales-part-open-${p.bsn_part_id}`}
                        variant="text"
                        onClick={() => setSelected(p.bsn_part_id)}
                      >
                        {p.bsn_part_nm}
                      </Button>{" "}
                      {p.hq_yn === "Y" && <Badge>본사</Badge>}
                    </td>
                    <td>{p.currentStandard?.ldr_nm ?? "–"}</td>
                    <td>
                      {p.currentStandard
                        ? `${p.currentStandard.prft_dstrb_rt}%`
                        : "–"}
                    </td>
                    <td>
                      {p.currentStandard
                        ? p.currentStandard.bsn_fee_amt.toLocaleString("ko-KR")
                        : "–"}
                    </td>
                    <td>{p.currentStandard?.aply_bgn_ym ?? "–"}</td>
                    <td>
                      {p.scheduledChanges.length
                        ? p.scheduledChanges.map((s) => (
                            <Badge key={s.part_std_hist_id} tone="warning">
                              {s.aply_bgn_ym}부터 {s.prft_dstrb_rt}%
                            </Badge>
                          ))
                        : "–"}
                    </td>
                    <td>{p.peopleCount}명</td>
                    <td>{p.projectCount}건</td>
                    <td>
                      <Badge tone={p.use_yn === "Y" ? "success" : "neutral"}>
                        {p.use_yn === "Y" ? "사용" : "중지"}
                      </Badge>
                    </td>
                  </tr>
                ))}
                {!data.items.length && (
                  <tr>
                    <td colSpan={renderDownload ? 10 : 9}>
                      조건에 맞는 결과가 없습니다
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <Pagination
            page={page}
            size={size}
            total={data.total}
            onPage={setPage}
            onSize={(n) => {
              setPage(1);
              setSize(n);
            }}
          />
        </>
      )}
      {selected && (
        <SalesPartDetailDrawer
          id={selected}
          onClose={close}
          renderActions={
            renderActions
              ? (part, onDetailChange, onActionDirty) =>
                  renderActions(part, onDetailChange, reload, onActionDirty)
              : undefined
          }
          onDirtyChange={onDirtyChange}
        />
      )}
      {create && data && (
        <div className={styles.writeActions}>
          <SalesPartCreateDialog
            page={data}
            onDirtyChange={onDirtyChange}
            onCancel={() => setCreate(false)}
            onSaved={(part) => {
              setCreate(false);
              setSuccess(`${part.bsn_part_nm}을 추가했습니다.`);
              setGeneration((n) => n + 1);
            }}
          />
        </div>
      )}
    </section>
  );
}
export { UI15Screen as SalesPartsScreen };
