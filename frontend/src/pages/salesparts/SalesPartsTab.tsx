import { useCallback, useState, type ReactNode } from "react";
import { Badge, Button, Pagination, Skeleton } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { useSharedPreferences } from "../shared/preferences";
import { SalesPartCreateDialog } from "./SalesPartCreateDialog";
import {
  getSalesPart,
  listSalesParts,
  type SalesPartDetail,
  type SalesPartStandard,
} from "./client";
import styles from "./SalesParts.module.css";

export type SalesPartAction = "standard" | "rename" | "disable" | "enable";
export type SalesPartActionHandler = (
  action: SalesPartAction,
  part: SalesPartDetail,
  refresh: () => Promise<unknown>,
) => void;
const period = (s: SalesPartStandard | null) =>
  s ? `${s.aply_bgn_ym} ~ ${s.aply_end_ym ?? "현재"}` : "–";
const money = (value: number | undefined) =>
  value === undefined ? "–" : `${value.toLocaleString("ko-KR")}원`;
type SalesPartActionRenderer = (
  part: SalesPartDetail,
  refresh: () => Promise<unknown>,
) => ReactNode;
function SalesPartDetailPanel({
  id,
  onClose,
  onAction,
  reload,
  renderActions,
}: {
  id: string;
  onClose: () => void;
  onAction?: SalesPartActionHandler;
  reload: () => Promise<unknown>;
  renderActions?: SalesPartActionRenderer;
}) {
  const read = useCallback(() => getSalesPart(id), [id]);
  const detail = useAdminRead(read);
  const [menu, setMenu] = useState(false);
  const p = detail.data,
    s = p?.currentStandard;
  const refresh = async () => {
    await Promise.all([detail.reload(), reload()]);
  };
  return (
    <aside
      className={styles.drawer}
      aria-label="영업 파트 상세"
      data-testid="sales-part-detail-panel"
    >
      <header>
        <div>
          <h2>
            {p?.bsn_part_nm ?? "영업 파트 상세"}{" "}
            {p?.hq_yn === "Y" && <Badge>본사</Badge>}
          </h2>
          {p && <p>{p.referenceMonth} 기준</p>}
        </div>
        <Button data-testid="sales-part-detail-close" onClick={onClose}>
          닫기
        </Button>
      </header>
      <ErrorBanner
        error={detail.error}
        onRetry={() => {
          void detail.reload().catch(() => {});
        }}
      />
      {detail.loading && <Skeleton />}
      {!detail.error && p && (
        <>
          {p.use_yn === "N" && (
            <p className={styles.notice}>사용을 중지한 파트입니다.</p>
          )}
          {renderActions
            ? renderActions(p, refresh)
            : p.hq_yn !== "Y" && (
                <div className={`${styles.actions} ${styles.mutations}`}>
                  <Can perm="UI-15.update">
                    <Button
                      data-testid="sales-part-standard-button"
                      disabled={!onAction}
                      title={
                        !onAction
                          ? "기준 변경 기능 연결이 필요합니다."
                          : undefined
                      }
                      onClick={() => onAction?.("standard", p, refresh)}
                    >
                      기준 변경
                    </Button>
                  </Can>
                  <Button
                    data-testid="sales-part-menu-button"
                    aria-label="파트 더보기"
                    aria-expanded={menu}
                    onClick={() => setMenu(!menu)}
                  >
                    ⋯
                  </Button>
                  {menu && (
                    <div className={styles.menu}>
                      <Can perm="UI-15.update">
                        <Button
                          data-testid="sales-part-rename-button"
                          disabled={!onAction}
                          onClick={() => onAction?.("rename", p, refresh)}
                        >
                          파트명 수정
                        </Button>
                      </Can>
                      <Can perm="UI-15.delete">
                        <Button
                          data-testid="sales-part-use-button"
                          disabled={!onAction}
                          onClick={() =>
                            onAction?.(
                              p.use_yn === "Y" ? "disable" : "enable",
                              p,
                              refresh,
                            )
                          }
                        >
                          {p.use_yn === "Y" ? "사용 중지" : "다시 사용"}
                        </Button>
                      </Can>
                    </div>
                  )}
                </div>
              )}
          <dl className={styles.info}>
            <dt>파트장</dt>
            <dd>{s?.ldr_nm ?? "–"}</dd>
            <dt>이익 배분율</dt>
            <dd>{s ? `${s.prft_dstrb_rt}%` : "–"}</dd>
            <dt>영업비(자사 인력 1인·월)</dt>
            <dd>{money(s?.bsn_fee_amt)}</dd>
            <dt>소속 인력</dt>
            <dd>{p.peopleCount}명</dd>
            <dt>진행 프로젝트</dt>
            <dd>{p.projectCount}건</dd>
            <dt>사용 여부</dt>
            <dd>{p.use_yn === "Y" ? "사용" : "중지"}</dd>
            <dt>적용 기간</dt>
            <dd>{period(s ?? null)}</dd>
          </dl>
          <h3>기준 이력</h3>
          <div className={styles.tableScroll}>
            <table>
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
                {p.standards.map((h) => (
                  <tr
                    data-testid={`sales-part-history-${h.part_std_hist_id}`}
                    key={h.part_std_hist_id}
                  >
                    <td>{period(h)}</td>
                    <td>{h.ldr_nm ?? "–"}</td>
                    <td>{h.prft_dstrb_rt}%</td>
                    <td>{money(h.bsn_fee_amt)}</td>
                    <td>
                      <Badge
                        tone={
                          h.status === "예정"
                            ? "info"
                            : h.status === "적용 중"
                              ? "success"
                              : "neutral"
                        }
                      >
                        {h.status}
                      </Badge>
                    </td>
                  </tr>
                ))}
                {!p.standards.length && (
                  <tr>
                    <td colSpan={5}>기준 이력이 없습니다.</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <p className={styles.notice}>
            {p.lastClosedMonth && p.firstAllowedMonth
              ? `마감된 달(${p.lastClosedMonth})까지의 정산에는 그때 기준이 그대로 쓰입니다. 새 기준은 ${p.firstAllowedMonth}부터 넣을 수 있습니다.`
              : "마감 이력이 없습니다. 적용 시작월을 직접 선택해 주세요."}
          </p>
        </>
      )}
    </aside>
  );
}

// SCR-UI-15: mount this panel under UI15Screen's 영업 파트 tab.
export function SalesPartsTab({
  onDirtyChange,
  onAction,
  renderActions,
  renderExport,
}: {
  onDirtyChange?: (dirty: boolean) => void;
  onAction?: SalesPartActionHandler;
  renderActions?: SalesPartActionRenderer;
  renderExport?: (
    columns: readonly string[],
    total: number,
    sort: string,
  ) => ReactNode;
}) {
  const [page, setPage] = useState(1),
    [sort, setSort] = useState(""),
    [selected, setSelected] = useState<string | null>(null),
    [create, setCreate] = useState(false),
    [success, setSuccess] = useState("");
  const size = useSharedPreferences((s) => s.pageSize),
    configure = useSharedPreferences((s) => s.configure);
  const read = useCallback(
    () => listSalesParts(page, size, sort),
    [page, size, sort],
  );
  const list = useAdminRead(read);
  const columns = [
    ["bsn_part_nm", "파트명"],
    ["currentStandard.ldr_nm", "파트장"],
    ["currentStandard.prft_dstrb_rt", "이익 배분율"],
    ["currentStandard.bsn_fee_amt", "영업비(1인·월)"],
    ["currentStandard.aply_bgn_ym", "적용 시작"],
    ["", "예정된 변경"],
    ["peopleCount", "소속 인력"],
    ["projectCount", "진행 프로젝트"],
    ["use_yn", "사용 여부"],
  ];
  return (
    <section
      data-testid="sales-parts-panel"
      aria-label="영업 파트"
      className={styles.panel}
    >
      <div className={styles.toolbar}>
        <h2>영업 파트</h2>
        <Can perm="UI-15.create">
          <Button
            data-testid="sales-part-add-button"
            className={styles.mutations}
            variant="primary"
            onClick={() => setCreate(true)}
          >
            + 파트 추가
          </Button>
        </Can>
      </div>
      <p className={styles.notice}>
        이익 배분율·영업비를 바꾸면 적용 시작월부터 새 기준을 씁니다. 지난 달
        정산은 바뀌지 않습니다. 기준 변경은 시스템 관리자·재무 담당만 할 수
        있습니다.
      </p>
      {success && <p role="status">{success}</p>}
      <ErrorBanner
        error={list.error}
        onRetry={() => {
          void list.reload().catch(() => {});
        }}
      />
      {list.loading && <Skeleton />}
      {list.data && !list.error && (
        <>
          <div className={styles.toolbar}>
            총 {list.data.total.toLocaleString("ko-KR")}건
            <div className={styles.mutations}>
              {renderExport?.(
                columns.map(([key]) => key || "scheduledChanges"),
                list.data.total,
                sort,
              )}
            </div>
          </div>
          <div className={styles.tableScroll}>
            <table>
              <thead>
                <tr>
                  {columns.map(([key, label]) => (
                    <th
                      key={label}
                      aria-sort={
                        sort.startsWith(`${key},`)
                          ? sort.endsWith("asc")
                            ? "ascending"
                            : "descending"
                          : undefined
                      }
                    >
                      {key ? (
                        <button
                          data-testid={`sales-part-sort-${key.replaceAll(".", "-").replaceAll("_", "-")}`}
                          onClick={() => {
                            setPage(1);
                            setSort(
                              `${key},${sort === `${key},asc` ? "desc" : "asc"}`,
                            );
                          }}
                        >
                          {label}
                        </button>
                      ) : (
                        label
                      )}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {list.data.items.map((p) => (
                  <tr
                    data-testid={`sales-part-row-${p.bsn_part_id}`}
                    key={p.bsn_part_id}
                    onClick={() => setSelected(p.bsn_part_id)}
                  >
                    <td>
                      <button
                        data-testid={`sales-part-open-${p.bsn_part_id}`}
                        className={styles.name}
                        onClick={() => setSelected(p.bsn_part_id)}
                      >
                        {p.bsn_part_nm}
                      </button>{" "}
                      {p.hq_yn === "Y" && <Badge>본사</Badge>}
                    </td>
                    <td>{p.currentStandard?.ldr_nm ?? "–"}</td>
                    <td>
                      {p.currentStandard
                        ? `${p.currentStandard.prft_dstrb_rt}%`
                        : "–"}
                    </td>
                    <td>{money(p.currentStandard?.bsn_fee_amt)}</td>
                    <td>{p.currentStandard?.aply_bgn_ym ?? "–"}</td>
                    <td>
                      {p.scheduledChanges.length
                        ? p.scheduledChanges.map((s) => (
                            <Badge tone="info" key={s.part_std_hist_id}>
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
                {!list.data.items.length && (
                  <tr>
                    <td colSpan={9}>조건에 맞는 결과가 없습니다</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <Pagination
            page={page}
            size={size}
            total={list.data.total}
            onPage={setPage}
            onSize={(value) => {
              configure({ pageSize: value });
              setPage(1);
            }}
          />
        </>
      )}
      {selected && (
        <SalesPartDetailPanel
          key={selected}
          id={selected}
          onClose={() => setSelected(null)}
          onAction={onAction}
          reload={list.reload}
          renderActions={renderActions}
        />
      )}
      {create && (
        <SalesPartCreateDialog
          onDirtyChange={onDirtyChange}
          reload={list.reload}
          onCancel={() => setCreate(false)}
          onSaved={(name) => {
            setCreate(false);
            setSuccess(`${name}을 추가했습니다.`);
          }}
        />
      )}
    </section>
  );
}
export default SalesPartsTab;
