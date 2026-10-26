import { useCallback, useState, type ReactNode } from "react";
import {
  Button,
  Badge,
  Tabs,
  Pagination,
  Skeleton,
  EmptyState,
  SuccessToast,
} from "../../shared/Controls";
import { ScreenHeading } from "../shared/ScreenHeading";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { Can } from "../../shared/Can";
import { useCodes } from "../shared/useCodes";
import { useAdminRead } from "../admin/useAdminResource";
import { useAdminGuard } from "../admin/AdminDialogs";
import {
  listPartners,
  defaultPartnerFilters,
  enablePartner,
  type PartnerFilters,
  type PartnerDetail,
  type PartnerListItem,
} from "./client";
import {
  ListExportButton,
  PartnerDisableDialog,
  type PartnerDisableRow,
} from "../partnersops/PartnerActions";
import { PartnerForm } from "./PartnerForm";
import { PartnerDrawer } from "./PartnerDrawer";
import { UploadDialogs } from "../uploadsops/UploadDialogs";
import styles from "./Partners.module.css";

const columns: readonly [keyof PartnerListItem, string][] = [
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
];
export default function UI15Screen({
  onDirtyChange,
  name = "거래처·영업 파트",
  groupName,
  salesParts,
  onUpload,
}: {
  onDirtyChange?: (dirty: boolean) => void;
  name?: string;
  groupName?: string;
  salesParts?: ReactNode;
  onUpload?: () => void;
}) {
  const [tab, setTab] = useState("partners"),
    [creating, setCreating] = useState(false),
    [dirty, setDirty] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [filters, setFilters] = useState<PartnerFilters>(defaultPartnerFilters),
    [draft, setDraft] = useState(defaultPartnerFilters);
  const [selected, setSelected] = useState<string[]>([]),
    [detail, setDetail] = useState<{ id: string; tab: string } | null>(null);
  const [highlight, setHighlight] = useState<PartnerListItem | null>(null),
    [message, setMessage] = useState("");
  const [error, setError] = useState<ApiFailure | null>(null),
    [disableRows, setDisableRows] = useState<PartnerDisableRow[] | null>(null);
  const guard = useAdminGuard(dirty);
  const kinds = useCodes("PARTNER_KIND");
  const read = useCallback(() => listPartners(filters), [filters]);
  const list = useAdminRead(read);
  const dirtyChange = useCallback(
    (value: boolean) => {
      setDirty(value);
      onDirtyChange?.(value);
    },
    [onDirtyChange],
  );
  const reset = () => {
    setDraft(defaultPartnerFilters);
    setFilters({ ...defaultPartnerFilters });
    setSelected([]);
    setHighlight(null);
  };
  const rows = highlight
    ? [
        highlight,
        ...(list.data?.items ?? []).filter(
          (r) => r.ptnr_id !== highlight.ptnr_id,
        ),
      ]
    : (list.data?.items ?? []);
  const open = (row: PartnerListItem, tab = "basic") =>
    setDetail({ id: row.ptnr_id, tab });
  const refresh = async (text: string) => {
    setMessage(text);
    setHighlight(null);
    return list.reload();
  };
  const stateAction = (row: PartnerDetail) => {
    if (row.use_yn === "Y") setDisableRows([row]);
    else {
      void enablePartner(row.ptnr_id)
        .then(() => {
          setDetail(null);
          return refresh("다시 사용합니다.");
        })
        .catch((e) => setError(e));
    }
  };
  return (
    <section data-screen-id="SCR-UI-15" data-testid="partners-screen">
      <ScreenHeading
        screenId="UI-15"
        name={creating ? "거래처 등록" : name}
        groupName={groupName}
        actions={
          !creating && tab === "partners" ? (
            <div className={styles.writeActions}>
              <Can perm="UI-15.download">
                <a
                  className={styles.linkButton}
                  data-testid="partner-template-download"
                  href="/api/uploads/templates/B.7"
                >
                  양식 내려받기
                </a>
              </Can>
              <Can perm="UI-15.upload">
                <Button
                  data-testid="partner-upload"
                  onClick={() => (onUpload ? onUpload() : setUploadOpen(true))}
                >
                  엑셀 업로드
                </Button>
              </Can>
              <Can perm="UI-15.create">
                <Button
                  data-testid="partner-create"
                  variant="primary"
                  onClick={() => {
                    setCreating(true);
                    setError(null);
                  }}
                >
                  + 등록
                </Button>
              </Can>
            </div>
          ) : undefined
        }
      />
      {creating ? (
        <>
          <PartnerForm
            onDirtyChange={dirtyChange}
            onCancel={() =>
              guard.request(() => {
                setCreating(false);
                dirtyChange(false);
              })
            }
            reload={list.reload}
            onSaved={(row) => {
              setCreating(false);
              dirtyChange(false);
              reset();
              setMessage("저장했습니다.");
              if (row)
                void listPartners({
                  ...defaultPartnerFilters,
                  q: row.biz_reg_no,
                })
                  .then((result) =>
                    setHighlight(
                      result.items.find(
                        (item) => item.ptnr_id === row.ptnr_id,
                      ) ?? null,
                    ),
                  )
                  .catch((e) => setError(e));
            }}
          />
          {guard.confirmation}
        </>
      ) : (
        <>
          <Tabs
            selected={tab}
            onChange={(t) => {
              setTab(t);
              setHighlight(null);
            }}
            items={[
              { key: "partners", label: "거래처" },
              { key: "salesParts", label: "영업 파트" },
            ]}
          />
          {tab === "partners" ? (
            <div role="tabpanel" data-testid="partners-panel">
              <form
                data-testid="partner-search-form"
                className={styles.search}
                onSubmit={(e) => {
                  e.preventDefault();
                  setFilters({ ...draft, page: 1 });
                  setSelected([]);
                  setHighlight(null);
                }}
              >
                <label>
                  구분
                  <select
                    aria-label="조회 구분"
                    data-testid="partner-kind-filter"
                    value={draft.kind}
                    onChange={(e) =>
                      setDraft({ ...draft, kind: e.target.value })
                    }
                  >
                    <option value="">전체</option>
                    {kinds.codes.map((c) => (
                      <option
                        data-testid={`partner-kind-option-${c.code.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`}
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
                    aria-label="사용 여부"
                    data-testid="partner-use-filter"
                    value={draft.useYn}
                    onChange={(e) =>
                      setDraft({ ...draft, useYn: e.target.value })
                    }
                  >
                    <option value="">전체</option>
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
                <Button
                  type="button"
                  data-testid="partner-reset"
                  onClick={reset}
                >
                  초기화
                </Button>
                <Button
                  type="submit"
                  data-testid="partner-query"
                  variant="primary"
                >
                  조회
                </Button>
              </form>
              <ErrorBanner error={kinds.error} onRetry={kinds.retry} />
              <ErrorBanner
                error={list.error}
                onRetry={() => void list.reload().catch(() => {})}
              />
              <ErrorBanner
                error={error}
                onRetry={() => {
                  setError(null);
                  void list.reload().catch(() => {});
                }}
              />
              <div className={styles.toolbar}>
                <span>총 {list.data?.total ?? 0}건</span>
                <Can perm="UI-15.download">
                  <ListExportButton
                    filter={{
                      kind: filters.kind,
                      useYn: filters.useYn,
                      q: filters.q,
                    }}
                    columns={columns.map(([key]) => key)}
                    selectedIds={selected}
                    total={list.data?.total ?? 0}
                  />
                </Can>
              </div>
              {selected.length > 0 && (
                <div className={`${styles.toolbar} ${styles.writeActions}`}>
                  <strong>{selected.length}건 선택</strong>
                  <Can perm="UI-15.delete">
                    <Button
                      data-testid="partner-disable-selected"
                      onClick={() =>
                        setDisableRows(
                          rows.filter((row) => selected.includes(row.ptnr_id)),
                        )
                      }
                    >
                      사용 중지
                    </Button>
                  </Can>
                </div>
              )}
              {list.loading && !list.data ? (
                <Skeleton />
              ) : (
                <div className={styles.tableScroll}>
                  <table className={styles.table}>
                    <thead>
                      <tr>
                        <th>
                          <input
                            type="checkbox"
                            aria-label="전체 선택"
                            data-testid="partner-select-all"
                            checked={
                              rows.length > 0 &&
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
                            data-testid={`partner-column-${key.replace(/_/g, "-").replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`)}`}
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
                              data-testid={`partner-sort-${key.replace(/_/g, "-").replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`)}`}
                              onClick={() => {
                                setHighlight(null);
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
                      {rows.map((row) => (
                        <tr
                          data-testid={`partner-row-${row.ptnr_id}`}
                          className={
                            row.ptnr_id === highlight?.ptnr_id
                              ? styles.highlight
                              : ""
                          }
                          key={row.ptnr_id}
                          onClick={() => open(row)}
                        >
                          <td
                            data-testid={`partner-selection-cell-${row.ptnr_id.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`}
                            onClick={(e) => e.stopPropagation()}
                          >
                            <input
                              data-testid={`partner-select-${row.ptnr_id}`}
                              aria-label={`${row.ptnr_nm} 선택`}
                              type="checkbox"
                              checked={selected.includes(row.ptnr_id)}
                              onChange={(e) =>
                                setSelected(
                                  e.target.checked
                                    ? [...selected, row.ptnr_id]
                                    : selected.filter(
                                        (id) => id !== row.ptnr_id,
                                      ),
                                )
                              }
                            />
                          </td>
                          {columns.map(([key]) => (
                            <td
                              data-testid={`partner-cell-${row.ptnr_id.toLowerCase().replace(/[^a-z0-9]+/g, "-")}-${key.replace(/_/g, "-").replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`)}`}
                              key={key}
                            >
                              {key === "ptnr_se_cd" ? (
                                <Badge
                                  tone={
                                    row.ptnr_se_cd === "CUST"
                                      ? "info"
                                      : "neutral"
                                  }
                                >
                                  {row.kindName}
                                </Badge>
                              ) : key === "use_yn" ? (
                                <Badge
                                  tone={
                                    row.use_yn === "Y" ? "success" : "neutral"
                                  }
                                >
                                  {row.use_yn === "Y" ? "사용" : "중지"}
                                </Badge>
                              ) : key === "ptnr_nm" ? (
                                <button
                                  data-testid={`partner-open-${row.ptnr_id}`}
                                  className={styles.textButton}
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    open(row);
                                  }}
                                >
                                  {row.ptnr_nm}
                                </button>
                              ) : key === "projectCount" ||
                                key === "assignmentPeopleCount" ? (
                                row[key] == null ? (
                                  "–"
                                ) : row[key]! > 0 ? (
                                  <button
                                    data-testid={`partner-${key === "projectCount" ? "projects" : "people"}-${row.ptnr_id}`}
                                    className={styles.countLink}
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
              {!list.loading && !list.error && rows.length === 0 && (
                <EmptyState
                  reason="조건에 맞는 결과가 없습니다."
                  actionLabel="조건 초기화"
                  onAction={reset}
                />
              )}
              <Pagination
                page={filters.page}
                size={filters.size}
                total={list.data?.total ?? 0}
                onPage={(page) => {
                  setSelected([]);
                  setHighlight(null);
                  setFilters({ ...filters, page });
                }}
                onSize={(size) => {
                  setSelected([]);
                  setHighlight(null);
                  setFilters({ ...filters, size, page: 1 });
                }}
              />
            </div>
          ) : (
            <div role="tabpanel" data-testid="partners-sales-parts-panel">
              {salesParts ?? <p>영업 파트 화면을 연결할 수 없습니다.</p>}
            </div>
          )}
        </>
      )}
      {detail && (
        <PartnerDrawer
          key={`${detail.id}-${detail.tab}`}
          id={detail.id}
          initialTab={detail.tab}
          onClose={() => setDetail(null)}
          onDirtyChange={dirtyChange}
          onSaved={refresh}
          onStateAction={stateAction}
        />
      )}
      {disableRows && (
        <PartnerDisableDialog
          rows={disableRows}
          onClose={() => setDisableRows(null)}
          reload={async () => {
            await list.reload();
            setSelected([]);
            setDetail(null);
            setHighlight(null);
          }}
          onComplete={setMessage}
        />
      )}
      {uploadOpen && (
        <UploadDialogs
          onClose={() => setUploadOpen(false)}
          onApplied={async (result) => {
            setMessage(`거래처 ${result.appliedRows}건을 반영했습니다.`);
            setHighlight(null);
            setSelected([]);
            await list.reload();
          }}
        />
      )}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
    </section>
  );
}
