import { useEffect, useRef, useState, type ReactNode } from "react";
import {
  Badge,
  Button,
  ConfirmDialog,
  Pagination,
  Skeleton,
  Tabs,
} from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { ScreenHeading } from "../shared/ScreenHeading";
import { useCodes } from "../shared/useCodes";
import { useAdminGuard } from "../admin/AdminDialogs";
import { useSharedPreferences } from "../shared/preferences";
import {
  listPartners,
  disablePartners,
  enablePartner,
  type PartnerDetail,
  type PartnerFilters,
  type PartnerListItem,
} from "./client";
import { PartnerForm } from "./PartnerForm";
import { PartnerDrawer } from "./PartnerDrawer";
import styles from "./Partners.module.css";

export const partnerColumns = [
  ["ptnr_se_cd", "구분"],
  ["ptnr_no", "거래처 코드"],
  ["ptnr_nm", "상호"],
  ["biz_reg_no", "사업자번호"],
  ["ceo_nm", "대표자"],
  ["industryName", "업종"],
  ["chrg_nm", "담당자"],
  ["chrg_tel_no", "연락처"],
  ["txinv_email_addr", "세금계산서 이메일"],
  ["paymentAccount", "지급 계좌"],
  ["projectCount", "진행 프로젝트"],
  ["assignmentPeopleCount", "투입 인력"],
  ["use_yn", "사용 여부"],
] as const;
const defaults: PartnerFilters = {
  kind: "",
  useYn: "Y",
  q: "",
  page: 1,
  size: 50,
  sort: "ptnr_nm,asc",
};
// SCR-UI-15: shared route registration renders this screen inside the existing shell.
export default function UI15Screen({
  name = "거래처·영업 파트",
  groupName,
  onDirtyChange,
  salesParts,
  onUpload,
  renderUploads,
  renderExport,
}: {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
  salesParts?: ReactNode;
  onUpload?: () => void;
  renderUploads?: (refresh: () => void) => ReactNode;
  renderExport?: (
    filters: PartnerFilters,
    ids: string[],
    total: number,
    columns: readonly string[],
  ) => ReactNode;
}) {
  const pageSize = useSharedPreferences((s) => s.pageSize),
    configure = useSharedPreferences((s) => s.configure);
  const [filters, setFilters] = useState<PartnerFilters>({
      ...defaults,
      size: pageSize,
    }),
    [draft, setDraft] = useState({ kind: "", useYn: "Y", q: "" });
  const [items, setItems] = useState<PartnerListItem[]>([]),
    [total, setTotal] = useState(0),
    [loading, setLoading] = useState(true),
    [error, setError] = useState<ApiFailure | null>(null),
    [revision, setRevision] = useState(0);
  const [tab, setTab] = useState("partners"),
    [create, setCreate] = useState(false),
    [dirty, setDirty] = useState(false),
    [selected, setSelected] = useState<string[]>([]);
  const [detail, setDetail] = useState<{ id: string; tab: string } | null>(
      null,
    ),
    [pinned, setPinned] = useState<PartnerListItem | null>(null),
    [message, setMessage] = useState("");
  const [statusIds, setStatusIds] = useState<string[] | null>(null),
    [pending, setPending] = useState(false);
  const kinds = useCodes("PARTNER_KIND"),
    guard = useAdminGuard(dirty);
  const dirtyReporter = useRef(onDirtyChange);
  dirtyReporter.current = onDirtyChange;
  useEffect(() => {
    dirtyReporter.current?.(dirty);
    return () => dirtyReporter.current?.(false);
  }, [dirty]);
  useEffect(() => {
    const abort = new AbortController();
    setLoading(true);
    setError(null);
    listPartners(filters, abort.signal)
      .then((p) => {
        setItems(p.items);
        setTotal(p.total);
        setSelected([]);
      })
      .catch((e) => {
        if (!abort.signal.aborted) setError(e);
      })
      .finally(() => {
        if (!abort.signal.aborted) setLoading(false);
      });
    return () => abort.abort();
  }, [filters, revision]);
  const reset = () => {
    setDraft({ kind: "", useYn: "Y", q: "" });
    setFilters({ ...defaults, size: filters.size });
    setPinned(null);
  };
  const open = (row: PartnerListItem, tab = "basic") =>
    setDetail({ id: row.ptnr_id, tab });
  const refresh = () => setRevision((r) => r + 1);
  async function created(row: PartnerDetail) {
    setCreate(false);
    setDirty(false);
    setMessage("저장했습니다.");
    reset();
    try {
      const p = await listPartners({ ...defaults, q: row.biz_reg_no ?? "" });
      setPinned(p.items.find((i) => i.ptnr_id === row.ptnr_id) ?? null);
    } catch (e) {
      setError(e as ApiFailure);
    }
    refresh();
  }
  async function changeStatus(row?: PartnerDetail) {
    if (row?.use_yn === "N") {
      setPending(true);
      try {
        await enablePartner(row.ptnr_id);
        setDetail(null);
        setMessage("다시 사용합니다.");
        refresh();
      } catch (e) {
        setError(e as ApiFailure);
      } finally {
        setPending(false);
      }
    } else setStatusIds(row ? [row.ptnr_id] : selected);
  }
  async function disable() {
    if (!statusIds || pending) return;
    setPending(true);
    try {
      const result = await disablePartners(statusIds);
      setStatusIds(null);
      setDetail(null);
      setMessage(
        `${result.data?.disabledIds.length ?? 0}건의 사용을 중지했습니다.${result.data?.blocked.map((b) => ` ${b.name}: ${b.reason}`).join("") ?? ""}`,
      );
      refresh();
    } catch (e) {
      setStatusIds(null);
      setError(e as ApiFailure);
    } finally {
      setPending(false);
    }
  }
  const visible = pinned
    ? [pinned, ...items.filter((i) => i.ptnr_id !== pinned.ptnr_id)]
    : items;
  return (
    <section data-testid="partners-screen" className={styles.screen}>
      <ScreenHeading
        screenId="UI-15"
        name={name}
        groupName={groupName}
        actions={
          !create &&
          tab === "partners" && (
            <div className={`${styles.actions} ${styles.mutation}`}>
              {renderUploads ? (
                renderUploads(refresh)
              ) : (
                <>
                  <Can perm="UI-15.download">
                    <a
                      data-testid="partner-template"
                      className={styles.download}
                      href="/api/uploads/templates/B.7"
                    >
                      양식 내려받기
                    </a>
                  </Can>
                  <Can perm="UI-15.upload">
                    <Button
                      data-testid="partner-upload"
                      onClick={() => {
                        if (onUpload) onUpload();
                        else
                          window.dispatchEvent(
                            new CustomEvent("partners-upload-open", {
                              detail: { form: "B.7" },
                            }),
                          );
                      }}
                    >
                      엑셀 업로드
                    </Button>
                  </Can>
                </>
              )}
              <Can perm="UI-15.create">
                <Button
                  data-testid="partner-create"
                  variant="primary"
                  onClick={() => setCreate(true)}
                >
                  + 등록
                </Button>
              </Can>
            </div>
          )
        }
      />
      <Tabs
        selected={tab}
        items={[
          { key: "partners", label: "거래처" },
          { key: "salesparts", label: "영업 파트" },
        ]}
        onChange={(t) =>
          guard.request(() => {
            setDirty(false);
            setCreate(false);
            setTab(t);
          })
        }
      />
      {message && <p role="status">{message}</p>}
      {tab === "salesparts" ? (
        (salesParts ?? <p>영업 파트 화면 연결이 필요합니다.</p>)
      ) : create ? (
        <>
          <h2>거래처 등록</h2>
          <PartnerForm
            onDirtyChange={setDirty}
            onCancel={() => {
              setCreate(false);
              setDirty(false);
            }}
            onSaved={(row) => void created(row)}
          />
        </>
      ) : (
        <>
          <form
            data-testid="partner-filters-form"
            className={styles.filters}
            onSubmit={(e) => {
              e.preventDefault();
              setPinned(null);
              setFilters({ ...filters, ...draft, page: 1 });
            }}
          >
            <label>
              구분
              <select
                data-testid="partner-kind-filter"
                aria-label="목록 구분"
                value={draft.kind}
                onChange={(e) => setDraft({ ...draft, kind: e.target.value })}
              >
                <option value="">전체</option>
                {kinds.codes.map((c) => (
                  <option
                    data-testid={`partner-kind-filter-option-${c.code.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`}
                    key={c.code}
                    value={c.code}
                  >
                    {c.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              사용 여부
              <select
                data-testid="partner-use-filter"
                aria-label="목록 사용 여부"
                value={draft.useYn}
                onChange={(e) => setDraft({ ...draft, useYn: e.target.value })}
              >
                <option value="Y">사용</option>
                <option value="N">중지</option>
              </select>
            </label>
            <label>
              상호·사업자번호
              <input
                data-testid="partner-search"
                aria-label="상호·사업자번호"
                placeholder="상호 또는 사업자번호"
                value={draft.q}
                onChange={(e) => setDraft({ ...draft, q: e.target.value })}
              />
            </label>
            <Button data-testid="partner-reset" type="button" onClick={reset}>
              초기화
            </Button>
            <Button data-testid="partner-query" type="submit" variant="primary">
              조회
            </Button>
          </form>
          <ErrorBanner error={kinds.error} onRetry={kinds.retry} />
          <ErrorBanner error={error} onRetry={refresh} />
          <div className={styles.toolbar}>
            <span>총 {total}건</span>
            {selected.length > 0 && (
              <>
                <span>{selected.length}건 선택</span>
                <Can perm="UI-15.delete">
                  <Button
                    data-testid="partner-disable-selected"
                    className={styles.mutation}
                    onClick={() => void changeStatus()}
                  >
                    사용 중지
                  </Button>
                </Can>
              </>
            )}
            <div className={styles.mutation}>
              {renderExport?.(
                filters,
                selected,
                total,
                partnerColumns.map(([key]) => key),
              )}
            </div>
          </div>
          {loading ? (
            <Skeleton />
          ) : (
            <div className={styles.tableScroll}>
              <table>
                <thead>
                  <tr>
                    <th>
                      <input
                        data-testid="partner-select-all"
                        type="checkbox"
                        aria-label="전체 선택"
                        checked={
                          visible.length > 0 &&
                          selected.length === visible.length
                        }
                        onChange={(e) =>
                          setSelected(
                            e.target.checked
                              ? visible.map((i) => i.ptnr_id)
                              : [],
                          )
                        }
                      />
                    </th>
                    {partnerColumns.map(([key, label]) => (
                      <th
                        data-testid={`partner-heading-${key.replaceAll("_", "-").toLowerCase()}`}
                        key={key}
                        aria-sort={
                          filters.sort.startsWith(key + ",")
                            ? filters.sort.endsWith("asc")
                              ? "ascending"
                              : "descending"
                            : "none"
                        }
                      >
                        <button
                          data-testid={`partner-sort-${key.replaceAll("_", "-")}`}
                          onClick={() => {
                            setPinned(null);
                            setFilters({
                              ...filters,
                              page: 1,
                              sort: `${key},${filters.sort === `${key},asc` ? "desc" : "asc"}`,
                            });
                          }}
                        >
                          {label}
                        </button>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {visible.map((row) => (
                    <tr
                      data-testid={`partner-row-${row.ptnr_id}`}
                      key={row.ptnr_id}
                      className={
                        pinned?.ptnr_id === row.ptnr_id ? styles.highlight : ""
                      }
                      onClick={() => open(row)}
                    >
                      <td
                        data-testid={`partner-selection-cell-${row.ptnr_id.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`}
                        onClick={(e) => e.stopPropagation()}
                      >
                        <input
                          data-testid={`partner-select-${row.ptnr_id}`}
                          type="checkbox"
                          aria-label={`${row.ptnr_nm} 선택`}
                          checked={selected.includes(row.ptnr_id)}
                          onChange={(e) =>
                            setSelected(
                              e.target.checked
                                ? [...selected, row.ptnr_id]
                                : selected.filter((id) => id !== row.ptnr_id),
                            )
                          }
                        />
                      </td>
                      {partnerColumns.map(([key]) => (
                        <td
                          data-testid={`partner-cell-${key.replaceAll("_", "-").toLowerCase()}-${row.ptnr_id.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`}
                          key={key}
                        >
                          {key === "ptnr_se_cd" ? (
                            <Badge tone="info">{row.kindName ?? "–"}</Badge>
                          ) : key === "use_yn" ? (
                            <Badge
                              tone={row.use_yn === "Y" ? "success" : "neutral"}
                            >
                              {row.use_yn === "Y" ? "사용" : "중지"}
                            </Badge>
                          ) : key === "ptnr_nm" ? (
                            <button
                              data-testid={`partner-open-${row.ptnr_id}`}
                              onClick={(e) => {
                                e.stopPropagation();
                                open(row);
                              }}
                            >
                              {row.ptnr_nm}
                            </button>
                          ) : key === "projectCount" ||
                            key === "assignmentPeopleCount" ? (
                            row[key] === null ? (
                              "–"
                            ) : row[key]! > 0 ? (
                              <button
                                data-testid={`partner-count-${key.toLowerCase()}-${row.ptnr_id}`}
                                onClick={(e) => {
                                  e.stopPropagation();
                                  open(row, "assignments");
                                }}
                              >
                                {row[key]}
                                {key === "projectCount" ? "건" : "명"}
                              </button>
                            ) : (
                              `0${key === "projectCount" ? "건" : "명"}`
                            )
                          ) : (
                            row[key] || "–"
                          )}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
              {!visible.length && !error && (
                <div className={styles.empty}>
                  <p>조건에 맞는 결과가 없습니다.</p>
                  <Button data-testid="partner-empty-reset" onClick={reset}>
                    조건 초기화
                  </Button>
                </div>
              )}
            </div>
          )}
          <Pagination
            page={filters.page}
            size={filters.size}
            total={total}
            onPage={(page) => {
              setPinned(null);
              setFilters({ ...filters, page });
            }}
            onSize={(size) => {
              configure({ pageSize: size });
              setPinned(null);
              setFilters({ ...filters, size, page: 1 });
            }}
          />
        </>
      )}
      {detail && (
        <PartnerDrawer
          key={detail.id}
          id={detail.id}
          peopleCount={
            visible.find((r) => r.ptnr_id === detail.id)?.assignmentPeopleCount
          }
          onDirtyChange={setDirty}
          initialTab={detail.tab}
          onClose={() => {
            setDirty(false);
            setDetail(null);
          }}
          onSaved={(_row, changed) => {
            setMessage(changed ? "저장했습니다." : "바뀐 내용이 없습니다.");
            refresh();
          }}
          onStatus={(row) => void changeStatus(row)}
        />
      )}
      {statusIds && (
        <ConfirmDialog
          title={`${statusIds.length}건의 사용을 중지할까요?`}
          destructive
          confirmLabel="사용 중지"
          onCancel={() => {
            if (!pending) setStatusIds(null);
          }}
          onConfirm={() => void disable()}
        >
          중지하면 새 프로젝트·지급에서 고를 수 없습니다. 기록은 그대로
          남습니다.
        </ConfirmDialog>
      )}
      {guard.confirmation}
    </section>
  );
}
