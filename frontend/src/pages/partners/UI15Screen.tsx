import { useState, type ReactNode } from "react";
import { ScreenHeading } from "../shared/ScreenHeading";
import {
  Button,
  Badge,
  Pagination,
  Skeleton,
  EmptyState,
  ConfirmDialog,
  SuccessToast,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { PartnerForm } from "./PartnerForm";
import { PartnerDrawer } from "./PartnerDrawer";
import { usePartnerRead } from "./usePartnerRead";
import {
  defaultPartnerFilters,
  listPartners,
  partnerQueryString,
  disablePartners,
  type PartnerFilters,
  type PartnerListItem,
} from "./client";
import { useCodes } from "../shared/useCodes";
import styles from "./Partners.module.css";

const columns: readonly [keyof PartnerListItem, string][] = [
  ["ptnr_se_cd", "구분"],
  ["ptnr_no", "거래처 코드"],
  ["ptnr_nm", "상호"],
  ["biz_reg_no", "사업자번호"],
  ["ceo_nm", "대표자"],
  ["indty_cd", "업종"],
  ["chrg_nm", "담당자"],
  ["chrg_tel_no", "연락처"],
  ["txinv_email_addr", "세금계산서 이메일"],
  ["paymentAccount", "지급 계좌"],
  ["projectCount", "진행 프로젝트"],
  ["assignmentPeopleCount", "투입 인력"],
  ["use_yn", "사용 여부"],
];
export default function UI15Screen({
  name = "거래처·영업 파트",
  groupName,
  onDirtyChange,
  onUpload,
  salesParts,
  renderUploads,
  renderExport,
  renderDisable,
  renderPartnerActions,
  renderActivity,
}: {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
  onUpload?: () => void;
  salesParts?: ReactNode | ((onPartners: () => void) => ReactNode);
  renderUploads?: (onApplied: () => void) => ReactNode;
  renderExport?: (
    filters: PartnerFilters,
    columns: string[],
    ids: string[],
    total: number,
  ) => ReactNode;
  renderDisable?: (
    rows: PartnerListItem[],
    onCancel: () => void,
    onChanged: (message: string) => void,
  ) => ReactNode;
  renderPartnerActions?: (
    row: import("./client").PartnerDetail,
    onChanged: (row: import("./client").PartnerDetail) => void,
  ) => ReactNode;
  renderActivity?: (id: string, tab: string) => ReactNode;
}) {
  const [filters, setFilters] = useState<PartnerFilters>({
      ...defaultPartnerFilters,
    }),
    [draft, setDraft] = useState({ ...defaultPartnerFilters }),
    [generation, setGeneration] = useState(0);
  const [tab, setTab] = useState("partners"),
    [creating, setCreating] = useState(false),
    [selected, setSelected] = useState<string[]>([]),
    [detail, setDetail] = useState<{ id: string; tab: string } | null>(null);
  const [pinned, setPinned] = useState<PartnerListItem | null>(null),
    [message, setMessage] = useState(""),
    [error, setError] = useState<ApiFailure | null>(null),
    [disableConfirm, setDisableConfirm] = useState(false),
    [pending, setPending] = useState(false);
  const read = usePartnerRead(
    `${partnerQueryString(filters)}:${generation}`,
    (signal) => listPartners(filters, signal),
  );
  const kinds = useCodes("PARTNER_KIND");
  const reset = () => {
    setDraft({ ...defaultPartnerFilters });
    setFilters({ ...defaultPartnerFilters });
    setSelected([]);
    setPinned(null);
    setGeneration((g) => g + 1);
  };
  const query = (next: PartnerFilters) => {
    setFilters(next);
    setSelected([]);
    setPinned(null);
  };
  const rows = pinned
    ? [
        pinned,
        ...(read.data?.items ?? []).filter((r) => r.ptnr_id !== pinned.ptnr_id),
      ]
    : (read.data?.items ?? []);
  const open = (row: PartnerListItem, tab = "basic") =>
    setDetail({ id: row.ptnr_id, tab });
  const disable = async () => {
    if (pending) return;
    setPending(true);
    setError(null);
    try {
      const response = await disablePartners(selected);
      const result = response.data!;
      setDisableConfirm(false);
      setMessage(
        result.disabledIds.length
          ? "사용을 중지했습니다."
          : "사용을 중지할 수 없습니다",
      );
      if (result.blocked.length)
        setError(
          new ApiFailure(
            result.blocked.map((r) => `${r.name}: ${r.reason}`).join(" / "),
            409,
            null,
          ),
        );
      setSelected([]);
      setGeneration((g) => g + 1);
    } catch (e) {
      setError(
        e instanceof ApiFailure
          ? e
          : new ApiFailure("요청을 처리할 수 없습니다.", null, null),
      );
    } finally {
      setPending(false);
    }
  };
  return (
    <section
      data-testid="partners-screen"
      data-screen-id="SCR-UI-15"
      className={styles.screen}
    >
      <ScreenHeading
        screenId="UI-15"
        name={name}
        groupName={groupName}
        actions={
          tab === "partners" && !creating ? (
            <div className={`${styles.actions} ${styles.desktopAction}`}>
              {renderUploads ? (
                renderUploads(() => {
                  setPinned(null);
                  setSelected([]);
                  setGeneration((g) => g + 1);
                })
              ) : (
                <>
                  <a
                    data-testid="partners-template-link"
                    className={styles.actionLink}
                    href="/api/uploads/templates/B.7"
                    download
                  >
                    양식 내려받기
                  </a>
                  <Button
                    data-testid="partners-upload-button"
                    disabled={!onUpload}
                    onClick={onUpload}
                  >
                    엑셀 업로드
                  </Button>
                </>
              )}
              <Button
                data-testid="partners-create-button"
                variant="primary"
                onClick={() => setCreating(true)}
              >
                등록
              </Button>
            </div>
          ) : undefined
        }
      />
      {!creating && tab === "partners" && (
        <div role="tablist" className={styles.tabs}>
          <button
            data-testid="partners-tab"
            role="tab"
            aria-selected={tab === "partners"}
            onClick={() => setTab("partners")}
          >
            거래처
          </button>
          <button
            data-testid="partners-sales-parts-tab"
            role="tab"
            aria-selected={false}
            disabled={!salesParts}
            onClick={() => setTab("salesParts")}
          >
            영업 파트
          </button>
        </div>
      )}
      {creating ? (
        <div className={styles.desktopAction}>
          <PartnerForm
            onDirtyChange={onDirtyChange}
            onCancel={() => {
              setCreating(false);
              onDirtyChange?.(false);
            }}
            onSaved={async (result) => {
              setCreating(false);
              onDirtyChange?.(false);
              reset();
              setMessage("저장했습니다.");
              try {
                const matches = await listPartners({
                  ...defaultPartnerFilters,
                  q: result.biz_reg_no,
                });
                setPinned(
                  matches.items.find((r) => r.ptnr_id === result.ptnr_id) ??
                    null,
                );
              } catch (e) {
                setError(
                  e instanceof ApiFailure
                    ? e
                    : new ApiFailure(
                        "신규 거래처를 다시 조회해 주세요.",
                        null,
                        null,
                      ),
                );
              }
            }}
          />
        </div>
      ) : tab === "salesParts" ? (
        typeof salesParts === "function" ? (
          salesParts(() => setTab("partners"))
        ) : (
          salesParts
        )
      ) : (
        <>
          <form
            data-testid="partners-filter-form"
            className={styles.filters}
            onSubmit={(e) => {
              e.preventDefault();
              query({ ...draft, page: 1 });
            }}
          >
            <label>
              구분
              <select
                data-testid="partners-kind-filter"
                aria-label="구분"
                value={draft.kind}
                onChange={(e) => setDraft({ ...draft, kind: e.target.value })}
              >
                <option value="">전체</option>
                {kinds.codes.map((c) => (
                  <option
                    data-testid={`partners-kind-option-${c.code.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`}
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
                data-testid="partners-use-filter"
                aria-label="사용 여부"
                value={draft.useYn}
                onChange={(e) => setDraft({ ...draft, useYn: e.target.value })}
              >
                <option value="">전체</option>
                <option value="Y">사용</option>
                <option value="N">중지</option>
              </select>
            </label>
            <label>
              상호·사업자번호
              <input
                data-testid="partners-search-input"
                aria-label="상호·사업자번호"
                placeholder="상호 또는 사업자번호"
                value={draft.q}
                onChange={(e) => setDraft({ ...draft, q: e.target.value })}
              />
            </label>
            <Button
              data-testid="partners-reset-button"
              type="button"
              onClick={reset}
            >
              초기화
            </Button>
            <Button
              data-testid="partners-search-button"
              type="submit"
              variant="primary"
            >
              조회
            </Button>
          </form>
          <ErrorBanner
            error={read.error ?? error}
            onRetry={() => {
              setError(null);
              read.retry();
            }}
          />
          <ErrorBanner error={kinds.error} onRetry={kinds.retry} />
          {read.loading && <Skeleton />}
          {read.data && (
            <>
              <div className={styles.toolbar}>
                <span>총 {read.data.total.toLocaleString("ko-KR")}건</span>
                <div className={styles.desktopAction}>
                  {renderExport ? (
                    renderExport(
                      filters,
                      columns.map(([key]) => key),
                      selected,
                      read.data.total,
                    )
                  ) : (
                    <a
                      data-testid="partners-export-link"
                      className={styles.actionLink}
                      href={`/api/partners/export?${partnerQueryString(filters)}${columns.map(([key]) => `&columns=${encodeURIComponent(key)}`).join("")}${selected.map((id) => `&ids=${encodeURIComponent(id)}`).join("")}`}
                    >
                      엑셀 다운로드
                    </a>
                  )}
                </div>
              </div>
              <div className={`${styles.toolbar} ${styles.desktopAction}`}>
                선택: {selected.length}건{" "}
                <Button
                  data-testid="partners-disable-button"
                  disabled={!selected.length || pending}
                  onClick={() => setDisableConfirm(true)}
                >
                  사용 중지
                </Button>
              </div>
              {!rows.length ? (
                <EmptyState
                  reason="조건에 맞는 결과가 없습니다."
                  actionLabel="조건 초기화"
                  onAction={reset}
                />
              ) : (
                <div className={styles.tableScroll}>
                  <table>
                    <thead>
                      <tr>
                        <th className={styles.desktopAction}>
                          <input
                            data-testid="partners-select-page"
                            type="checkbox"
                            aria-label="현재 페이지 전체 선택"
                            checked={
                              !!rows.length &&
                              rows.every((r) => selected.includes(r.ptnr_id))
                            }
                            onChange={(e) =>
                              setSelected(
                                e.target.checked
                                  ? rows.map((r) => r.ptnr_id)
                                  : [],
                              )
                            }
                          />
                        </th>
                        {columns.map(([key, label]) => (
                          <th
                            data-testid={`partners-header-${key.replace(/_/g, "-").replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`)}`}
                            key={key}
                            aria-sort={
                              filters.sort.startsWith(`${key},`)
                                ? filters.sort.endsWith("asc")
                                  ? "ascending"
                                  : "descending"
                                : "none"
                            }
                          >
                            <button
                              data-testid={`partners-sort-${key.replace(/_/g, "-").replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`)}`}
                              onClick={() =>
                                query({
                                  ...filters,
                                  page: 1,
                                  sort: `${key},${filters.sort === `${key},asc` ? "desc" : "asc"}`,
                                })
                              }
                            >
                              {label}
                            </button>
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {rows.map((row) => (
                        <tr
                          data-testid={`partner-row-${row.ptnr_id}`}
                          key={row.ptnr_id}
                          className={
                            pinned?.ptnr_id === row.ptnr_id
                              ? styles.highlight
                              : undefined
                          }
                          onClick={() => open(row)}
                        >
                          <td
                            data-testid={`partner-select-cell-${row.ptnr_id}`}
                            className={styles.desktopAction}
                            onClick={(e) => e.stopPropagation()}
                          >
                            <input
                              data-testid={`partner-select-${row.ptnr_id}`}
                              type="checkbox"
                              aria-label={`${row.ptnr_nm} 선택`}
                              checked={selected.includes(row.ptnr_id)}
                              onChange={(e) =>
                                setSelected((ids) =>
                                  e.target.checked
                                    ? [...ids, row.ptnr_id]
                                    : ids.filter((id) => id !== row.ptnr_id),
                                )
                              }
                            />
                          </td>
                          {columns.map(([key]) => (
                            <td
                              data-testid={`partner-cell-${row.ptnr_id}-${key.replace(/_/g, "-").replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`)}`}
                              key={key}
                            >
                              {key === "ptnr_se_cd" ? (
                                <Badge>{row.kindName}</Badge>
                              ) : key === "use_yn" ? (
                                <Badge
                                  tone={
                                    row.use_yn === "Y" ? "success" : "neutral"
                                  }
                                >
                                  {row.use_yn === "Y" ? "사용" : "중지"}
                                </Badge>
                              ) : key === "indty_cd" ? (
                                (row.industryName ?? "–")
                              ) : key === "ptnr_nm" ? (
                                <button
                                  data-testid={`partner-detail-${row.ptnr_id}`}
                                  className={styles.link}
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    open(row);
                                  }}
                                >
                                  {row.ptnr_nm}
                                  {pinned?.ptnr_id === row.ptnr_id && (
                                    <span> 신규</span>
                                  )}
                                </button>
                              ) : key === "projectCount" ||
                                key === "assignmentPeopleCount" ? (
                                row[key] === null ? (
                                  "–"
                                ) : Number(row[key]) > 0 ? (
                                  <button
                                    data-testid={`partner-${key === "projectCount" ? "projects" : "people"}-${row.ptnr_id}`}
                                    className={styles.link}
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
                                (row[key] ?? "–")
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
                page={read.data.page}
                size={filters.size}
                total={read.data.total}
                onPage={(page) => query({ ...filters, page })}
                onSize={(size) => {
                  setDraft((d) => ({ ...d, size }));
                  query({ ...filters, page: 1, size });
                }}
              />
            </>
          )}
        </>
      )}
      {detail && (
        <PartnerDrawer
          key={detail.id}
          id={detail.id}
          initialTab={detail.tab}
          onDirtyChange={onDirtyChange}
          renderActions={renderPartnerActions}
          renderActivity={renderActivity}
          onClose={() => {
            setDetail(null);
            onDirtyChange?.(false);
          }}
          onSaved={() => {
            setPinned(null);
            setGeneration((g) => g + 1);
          }}
        />
      )}
      {disableConfirm &&
        (renderDisable ? (
          renderDisable(
            rows.filter((row) => selected.includes(row.ptnr_id)),
            () => setDisableConfirm(false),
            (text) => {
              setMessage(text);
              setSelected([]);
              setPinned(null);
              setGeneration((g) => g + 1);
            },
          )
        ) : (
          <ConfirmDialog
            title="사용을 중지할까요?"
            confirmLabel="사용 중지"
            destructive
            onCancel={() => {
              if (!pending) setDisableConfirm(false);
            }}
            onConfirm={() => void disable()}
          >
            선택한 {selected.length}건은 새 프로젝트·지급에서 선택할 수
            없습니다.
          </ConfirmDialog>
        ))}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
    </section>
  );
}
