import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import {
  Badge,
  Button,
  Pagination,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { formatMoney } from "../../lib/format";
import { useAdminRead } from "../admin/useAdminResource";
import { failure } from "../admin/client";
import {
  getSalesPart,
  listSalesParts,
  type SalesPartDetail,
  type SalesPartStandard,
} from "./client";
import { SalesPartCreateDialog } from "./SalesPartCreateDialog";
import styles from "./SalesParts.module.css";

export type SalesPartAction = "standards" | "name" | "disable" | "enable";
export type SalesPartActionHandler = (
  action: SalesPartAction,
  part: SalesPartDetail,
  refresh: () => Promise<unknown>,
) => void;
const standardTone = (status: SalesPartStandard["status"]) =>
  status === "예정" ? "warning" : status === "적용 중" ? "success" : "neutral";
const period = (standard: SalesPartStandard | null) =>
  standard
    ? `${standard.aply_bgn_ym} ~ ${standard.aply_end_ym ?? "현재"}`
    : "–";

// SCR-UI-15: mounted by the existing UI-15 영업 파트 tab, not a separate route.
export function SalesPartsTab({
  onAction,
  onDirtyChange,
  renderExport,
}: {
  onAction?: SalesPartActionHandler;
  onDirtyChange?: (dirty: boolean) => void;
  renderExport?: (ids: string[], total: number) => ReactNode;
}) {
  const [page, setPage] = useState(1),
    [size, setSize] = useState<20 | 50 | 100>(50),
    [sort, setSort] = useState("");
  const read = useCallback(
    () => listSalesParts(page, size, sort),
    [page, size, sort],
  );
  const list = useAdminRead(read);
  const [selected, setSelected] = useState("");
  const [checked, setChecked] = useState<string[]>([]);
  const readDetail = useCallback(
    () => (selected ? getSalesPart(selected) : Promise.resolve(null)),
    [selected],
  );
  const detail = useAdminRead(readDetail);
  const [createBounds, setCreateBounds] = useState<{
    firstAllowedMonth: string | null;
    lastClosedMonth: string | null;
  } | null>(null);
  const [opening, setOpening] = useState(false),
    [openError, setOpenError] = useState<ApiFailure | null>(null),
    [success, setSuccess] = useState("");
  const hq = useRef("");
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  useEffect(() => {
    const row = list.data?.items.find((p) => p.hq_yn === "Y");
    if (row) hq.current = row.bsn_part_id;
  }, [list.data]);
  const openCreate = async () => {
    if (opening) return;
    setOpening(true);
    setOpenError(null);
    try {
      let id = hq.current;
      if (!id)
        id =
          (await listSalesParts()).items.find((p) => p.hq_yn === "Y")
            ?.bsn_part_id ?? "";
      if (!id)
        throw new ApiFailure(
          "본사 파트를 찾을 수 없습니다. 파트 조회를 다시 시도해 주세요.",
          null,
          null,
        );
      const bounds = await getSalesPart(id);
      if (mounted.current)
        setCreateBounds({
          firstAllowedMonth: bounds.firstAllowedMonth,
          lastClosedMonth: bounds.lastClosedMonth,
        });
    } catch (e) {
      if (mounted.current) setOpenError(failure(e));
    } finally {
      if (mounted.current) setOpening(false);
    }
  };
  const refresh = async () => {
    await list.reload();
    if (selected) await detail.reload();
  };
  const sortColumn = (key: string) => {
    setPage(1);
    setSort(sort === `${key},asc` ? `${key},desc` : `${key},asc`);
  };
  const columns = [
    ["bsn_part_nm", "파트명"],
    ["currentStandard.ldr_nm", "파트장"],
    ["currentStandard.prft_dstrb_rt", "이익 배분율 (%)"],
    ["currentStandard.bsn_fee_amt", "영업비 (1인·월, 원)"],
    ["currentStandard.aply_bgn_ym", "적용 시작"],
    ["", "예정된 변경"],
    ["peopleCount", "소속 인력"],
    ["projectCount", "진행 프로젝트"],
    ["use_yn", "사용"],
  ];
  return (
    <section
      aria-label="영업 파트"
      data-screen-id="SCR-UI-15"
      data-testid="sales-parts-panel"
      role="tabpanel"
    >
      <div className={styles.toolbar}>
        <h2>영업 파트</h2>
        <Can perm="UI-15.create">
          <Button
            data-testid="sales-part-add"
            className={styles.writeAction}
            variant="primary"
            pending={opening}
            onClick={() => {
              void openCreate();
            }}
          >
            + 파트 추가
          </Button>
        </Can>
      </div>
      <div className={styles.info}>
        이익 배분율·영업비를 바꾸면 적용 시작월부터 새 기준을 씁니다.
        <br />
        지난 달 정산은 바뀌지 않습니다.
        <br />
        기준 변경은 시스템 관리자·재무 담당만 할 수 있습니다.
      </div>
      <ErrorBanner
        error={openError}
        onRetry={() => {
          void openCreate();
        }}
        pending={opening}
      />
      <ErrorBanner
        error={list.error}
        onRetry={() => {
          void list.reload().catch(() => {});
        }}
      />
      {list.loading && <Skeleton />}
      <div className={styles.toolbar}>
        <p>총 {list.data?.total.toLocaleString("ko-KR") ?? "–"}건</p>
        {renderExport?.(checked, list.data?.total ?? 0)}
      </div>
      <div className={styles.tableScroll}>
        <table className={styles.table}>
          <thead>
            <tr>
              {renderExport && (
                <th>
                  <input
                    data-testid="sales-parts-select-all"
                    type="checkbox"
                    aria-label="영업 파트 전체 선택"
                    checked={
                      !!list.data?.items.length &&
                      list.data.items.every((row) =>
                        checked.includes(row.bsn_part_id),
                      )
                    }
                    onChange={(e) =>
                      setChecked(
                        e.target.checked
                          ? (list.data?.items.map((row) => row.bsn_part_id) ??
                              [])
                          : [],
                      )
                    }
                  />
                </th>
              )}
              {columns.map(([key, label]) => (
                <th
                  data-testid={`sales-parts-column-${
                    key
                      ? key
                          .replaceAll(".", "-")
                          .replaceAll("_", "-")
                          .replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`)
                      : "scheduled-changes"
                  }`}
                  key={label}
                  aria-sort={
                    key && sort.startsWith(`${key},`)
                      ? sort.endsWith(",asc")
                        ? "ascending"
                        : "descending"
                      : undefined
                  }
                >
                  {key ? (
                    <button
                      data-testid={`sales-parts-sort-${key.replaceAll(".", "-").replaceAll("_", "-")}`}
                      onClick={() => sortColumn(key)}
                    >
                      {label}
                      {sort.startsWith(`${key},`)
                        ? sort.endsWith(",asc")
                          ? " ↑"
                          : " ↓"
                        : ""}
                    </button>
                  ) : (
                    label
                  )}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {!list.loading && !list.error && !list.data?.items.length && (
              <tr>
                <td colSpan={renderExport ? 10 : 9}>
                  <div className={styles.empty}>
                    조건에 맞는 결과가 없습니다
                  </div>
                </td>
              </tr>
            )}
            {!list.error &&
              list.data?.items.map((row) => (
                <tr
                  key={row.bsn_part_id}
                  data-testid={`sales-part-row-${row.bsn_part_id}`}
                  onClick={() => setSelected(row.bsn_part_id)}
                >
                  {renderExport && (
                    <td onClick={(e) => e.stopPropagation()}>
                      <input
                        data-testid={`sales-part-select-${row.bsn_part_id}`}
                        type="checkbox"
                        aria-label={`${row.bsn_part_nm} 선택`}
                        checked={checked.includes(row.bsn_part_id)}
                        onChange={(e) =>
                          setChecked(
                            e.target.checked
                              ? [...checked, row.bsn_part_id]
                              : checked.filter((id) => id !== row.bsn_part_id),
                          )
                        }
                      />
                    </td>
                  )}
                  <td>
                    <button
                      data-testid={`sales-part-open-${row.bsn_part_id}`}
                      className={styles.rowLink}
                      onClick={() => setSelected(row.bsn_part_id)}
                    >
                      {row.bsn_part_nm}
                    </button>{" "}
                    {row.hq_yn === "Y" && <Badge tone="info">본사</Badge>}
                  </td>
                  <td>{row.currentStandard?.ldr_nm ?? "–"}</td>
                  <td className={styles.number}>
                    {row.currentStandard
                      ? `${row.currentStandard.prft_dstrb_rt}%`
                      : "–"}
                  </td>
                  <td className={styles.number}>
                    {formatMoney(row.currentStandard?.bsn_fee_amt)}
                  </td>
                  <td>{row.currentStandard?.aply_bgn_ym ?? "–"}</td>
                  <td>
                    {row.scheduledChanges.length
                      ? row.scheduledChanges.map((s) => (
                          <div key={s.part_std_hist_id}>
                            <Badge tone="warning">
                              {s.aply_bgn_ym}부터 {s.prft_dstrb_rt}%
                            </Badge>
                          </div>
                        ))
                      : "–"}
                  </td>
                  <td className={styles.number}>{row.peopleCount}명</td>
                  <td className={styles.number}>{row.projectCount}건</td>
                  <td>
                    <Badge tone={row.use_yn === "Y" ? "success" : "neutral"}>
                      {row.use_yn === "Y" ? "사용" : "중지"}
                    </Badge>
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>
      <Pagination
        page={page}
        size={size}
        total={list.data?.total ?? 0}
        onPage={setPage}
        onSize={(next) => {
          setSize(next);
          setPage(1);
        }}
      />
      {selected && (
        <SalesPartDetailPanel
          part={detail.data?.bsn_part_id === selected ? detail.data : null}
          loading={detail.loading}
          error={detail.error}
          onRetry={() => {
            void detail.reload().catch(() => {});
          }}
          onClose={() => setSelected("")}
          onAction={onAction}
          refresh={refresh}
        />
      )}
      {createBounds && (
        <SalesPartCreateDialog
          firstAllowedMonth={createBounds.firstAllowedMonth}
          lastClosedMonth={createBounds.lastClosedMonth}
          onDirtyChange={onDirtyChange}
          reload={list.reload}
          onCancel={() => setCreateBounds(null)}
          onSaved={(created) => {
            setCreateBounds(null);
            setSuccess(`${created.bsn_part_nm}을 추가했습니다.`);
          }}
        />
      )}
      {success && (
        <SuccessToast message={success} onDismiss={() => setSuccess("")} />
      )}
    </section>
  );
}

export function SalesPartDetailPanel({
  part,
  loading,
  error,
  onRetry,
  onClose,
  onAction,
  refresh,
}: {
  part: SalesPartDetail | null;
  loading: boolean;
  error: ApiFailure | null;
  onRetry: () => void;
  onClose: () => void;
  onAction?: SalesPartActionHandler;
  refresh: () => Promise<unknown>;
}) {
  const [menu, setMenu] = useState(false);
  const panel = useRef<HTMLElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    panel.current?.focus();
    return () => previous?.focus();
  }, []);
  const action = (key: SalesPartAction) => {
    setMenu(false);
    if (part) onAction?.(key, part, refresh);
  };
  return (
    <div
      className={styles.detailBackdrop}
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <aside
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-label="영업 파트 상세"
        tabIndex={-1}
        data-testid="sales-part-detail"
        className={styles.detail}
        onKeyDown={(e) => {
          if (e.key === "Escape") {
            e.stopPropagation();
            onClose();
          }
          if (e.key === "Tab") {
            const controls = Array.from(
              panel.current?.querySelectorAll<HTMLElement>(
                'button:not(:disabled), [tabindex="0"]',
              ) ?? [],
            );
            const first = controls[0],
              last = controls[controls.length - 1];
            if (
              e.shiftKey &&
              (document.activeElement === first ||
                document.activeElement === panel.current)
            ) {
              e.preventDefault();
              last?.focus();
            } else if (!e.shiftKey && document.activeElement === last) {
              e.preventDefault();
              first?.focus();
            }
          }
        }}
      >
        <div className={styles.detailHeader}>
          <div>
            <h2>{part?.bsn_part_nm ?? "영업 파트 상세"}</h2>
            {part && (
              <p>
                {part.referenceMonth} 기준{" "}
                {part.hq_yn === "Y" && <Badge tone="info">본사</Badge>}
              </p>
            )}
          </div>
          {part && part.hq_yn !== "Y" && (
            <div className={styles.writeAction}>
              <Can perm="UI-15.update">
                <Button
                  data-testid="sales-part-change-standard"
                  disabled={!onAction || loading}
                  onClick={() => action("standards")}
                >
                  기준 변경
                </Button>
              </Can>
              <Button
                data-testid="sales-part-more"
                aria-label="파트 더보기"
                aria-expanded={menu}
                onClick={() => setMenu(!menu)}
              >
                ⋯
              </Button>
              {menu && (
                <div className={styles.menu} role="menu">
                  <Can perm="UI-15.update">
                    <Button
                      role="menuitem"
                      data-testid="sales-part-rename"
                      disabled={!onAction}
                      onClick={() => action("name")}
                    >
                      파트명 수정
                    </Button>
                  </Can>
                  <Can perm="UI-15.delete">
                    <Button
                      role="menuitem"
                      data-testid="sales-part-toggle-use"
                      disabled={!onAction}
                      onClick={() =>
                        action(part.use_yn === "Y" ? "disable" : "enable")
                      }
                    >
                      {part.use_yn === "Y" ? "사용 중지" : "다시 사용"}
                    </Button>
                  </Can>
                </div>
              )}
            </div>
          )}
          <Button
            data-testid="sales-part-detail-close"
            aria-label="상세 닫기"
            onClick={onClose}
          >
            닫기
          </Button>
        </div>
        {loading && <Skeleton />}
        <ErrorBanner error={error} onRetry={onRetry} />
        {part && !loading && !error && (
          <>
            {part.use_yn === "N" && (
              <div className={styles.warning}>사용을 중지한 파트입니다.</div>
            )}
            <dl className={styles.information}>
              <dt>파트장</dt>
              <dd>{part.currentStandard?.ldr_nm ?? "–"}</dd>
              <dt>이익 배분율</dt>
              <dd>
                {part.currentStandard
                  ? `${part.currentStandard.prft_dstrb_rt}%`
                  : "–"}
              </dd>
              <dt>영업비 (자사 인력 1인·월)</dt>
              <dd>
                {formatMoney(part.currentStandard?.bsn_fee_amt)}
                {part.currentStandard && "원"}
              </dd>
              <dt>소속 인력</dt>
              <dd>{part.peopleCount}명</dd>
              <dt>진행 프로젝트</dt>
              <dd>{part.projectCount}건</dd>
              <dt>사용 여부</dt>
              <dd>
                <Badge tone={part.use_yn === "Y" ? "success" : "neutral"}>
                  {part.use_yn === "Y" ? "사용" : "중지"}
                </Badge>
              </dd>
              <dt>적용 기간</dt>
              <dd>{period(part.currentStandard)}</dd>
            </dl>
            <h3>기준 이력</h3>
            <div className={styles.tableScroll}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>적용 기간</th>
                    <th>파트장</th>
                    <th>배분율</th>
                    <th>영업비</th>
                    <th>상태</th>
                  </tr>
                </thead>
                <tbody>
                  {part.standards.map((s) => (
                    <tr
                      key={s.part_std_hist_id}
                      data-testid={`sales-part-standard-${s.part_std_hist_id}`}
                    >
                      <td>{period(s)}</td>
                      <td>{s.ldr_nm ?? "–"}</td>
                      <td className={styles.number}>{s.prft_dstrb_rt}%</td>
                      <td className={styles.number}>
                        {formatMoney(s.bsn_fee_amt)}원
                      </td>
                      <td>
                        <Badge tone={standardTone(s.status)}>{s.status}</Badge>
                      </td>
                    </tr>
                  ))}
                  {!part.standards.length && (
                    <tr>
                      <td colSpan={5}>
                        <div className={styles.empty}>
                          기준 이력이 없습니다.
                        </div>
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            <p className={styles.info}>
              {part.lastClosedMonth && part.firstAllowedMonth ? (
                <>
                  마감된 달({part.lastClosedMonth})까지의 정산에는 그때 기준이
                  그대로 쓰입니다. 새 기준은 {part.firstAllowedMonth}부터 넣을
                  수 있습니다.
                </>
              ) : (
                "마감 이력이 없습니다. 적용 시작월을 직접 선택할 수 있습니다."
              )}
            </p>
          </>
        )}
      </aside>
    </div>
  );
}

export default SalesPartsTab;
