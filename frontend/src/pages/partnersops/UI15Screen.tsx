import { useEffect, useState, type ReactNode } from "react";
import { readData, type ApiFailure } from "../../shared/api";
import {
  Button,
  Tabs,
  Skeleton,
  SearchBar,
  Pagination,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ScreenHeading } from "../shared/ScreenHeading";
import { useCodes } from "../shared/useCodes";
import {
  PartnerActivityTabs,
  PartnerDisableDialog,
  PartnerExportButton,
  partnerFailure,
} from "./PartnerActions";
import {
  partnerQuery,
  savePartnerEnable,
  type PartnerDetail,
  type PartnerRow,
  type PartnerPage,
  type SalesPartRow,
} from "./client";
import styles from "./Partners.module.css";

export const partnerDisplayColumns = [
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
  ["assignmentPeopleCount", "투입 인원"],
  ["use_yn", "사용 여부"],
] as const;
const salesColumns = [
  ["bsn_part_nm", "파트명"],
  ["ldr_nm", "파트장"],
  ["prft_dstrb_rt", "이익 배분율"],
  ["bsn_fee_amt", "영업비(1인·월)"],
  ["aply_bgn_ym", "적용 시작"],
  ["scheduledChanges", "예정된 변경"],
  ["peopleCount", "소속 인력"],
  ["projectCount", "진행 프로젝트"],
  ["use_yn", "사용 여부"],
] as const;
function usePartnerMobile() {
  const [mobile, setMobile] = useState(
    () => window.matchMedia("(max-width: 767px)").matches,
  );
  useEffect(() => {
    const query = window.matchMedia("(max-width: 767px)");
    const update = () => setMobile(query.matches);
    query.addEventListener("change", update);
    return () => query.removeEventListener("change", update);
  }, []);
  return mobile;
}
export function PartnerDetailDrawer({
  id,
  onClose,
  onChanged,
  initial = "basic",
  actions,
}: {
  id: string;
  onClose: () => void;
  onChanged: (message: string) => void;
  initial?: string;
  actions?: ReactNode;
}) {
  const [detail, setDetail] = useState<PartnerDetail | null>(null),
    [error, setError] = useState<ApiFailure | null>(null),
    [tab, setTab] = useState(initial),
    [disable, setDisable] = useState(false),
    [pending, setPending] = useState(false),
    [version, setVersion] = useState(0);
  const mobile = usePartnerMobile();
  useEffect(() => {
    let live = true;
    setDetail(null);
    setError(null);
    readData<PartnerDetail>(`/api/partners/${encodeURIComponent(id)}`)
      .then((d) => {
        if (live) setDetail(d);
      })
      .catch((e) => {
        if (live) setError(partnerFailure(e));
      });
    return () => {
      live = false;
    };
  }, [id, version]);
  const enable = async () => {
    if (pending) return;
    setPending(true);
    try {
      const row = await savePartnerEnable(id);
      setDetail(row);
      onChanged("다시 사용하도록 변경했습니다.");
    } catch (e) {
      setError(partnerFailure(e));
    } finally {
      setPending(false);
    }
  };
  const changed = (message: string) => {
    onChanged(message);
    setVersion((v) => v + 1);
  };
  return (
    <div
      data-testid="partner-detail-backdrop"
      className={styles.backdrop}
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <section
        role="dialog"
        aria-modal="true"
        aria-label="거래처 상세"
        tabIndex={-1}
        className={styles.drawer}
        data-testid="partner-detail-drawer"
        onKeyDown={(e) => {
          if (e.key === "Escape") onClose();
        }}
      >
        <header>
          <h2>{detail?.ptnr_nm ?? "거래처 상세"}</h2>
          <Button data-testid="partner-detail-close" onClick={onClose}>
            닫기
          </Button>
          {!mobile && detail && (
            <div className={styles.writeOnly}>
              {actions}
              <details>
                <summary data-testid="partner-detail-more">더보기</summary>
                {detail.use_yn === "Y" ? (
                  <Button
                    data-testid="partner-detail-disable"
                    onClick={() => setDisable(true)}
                  >
                    사용 중지
                  </Button>
                ) : (
                  <Button
                    data-testid="partner-detail-enable"
                    pending={pending}
                    onClick={() => void enable()}
                  >
                    다시 사용
                  </Button>
                )}
              </details>
            </div>
          )}
        </header>
        <ErrorBanner error={error} onRetry={() => setVersion((v) => v + 1)} />
        {!detail && !error && <Skeleton />}
        {detail && (
          <>
            {detail.use_yn === "N" && (
              <p className={styles.notice}>
                사용을 중지한 거래처입니다. 새 프로젝트·지급에 고를 수 없습니다.
              </p>
            )}
            <Tabs
              selected={tab}
              onChange={setTab}
              items={[
                { key: "basic", label: "기본 정보" },
                { key: "assignments", label: "투입 내역" },
                { key: "history", label: "변경 이력" },
              ]}
            />
            {tab === "basic" ? (
              <dl data-testid="partner-basic-panel" role="tabpanel">
                {[
                  ["거래처 코드", detail.ptnr_no],
                  ["구분", detail.ptnr_se_cd],
                  ["사업자번호", detail.biz_reg_no],
                  ["대표자", detail.ceo_nm],
                  ["업종", detail.indty_cd],
                  ["담당자", detail.chrg_nm],
                  ["연락처", detail.chrg_tel_no],
                  ["비고", detail.rmrk_desc],
                  ...(detail.ptnr_se_cd !== "VEND"
                    ? [["세금계산서 이메일", detail.txinv_email_addr]]
                    : []),
                  ...(detail.ptnr_se_cd !== "CUST"
                    ? [
                        ["은행", detail.bank_cd],
                        ["계좌번호", detail.acnt_no],
                        ["예금주", detail.dpstr_nm],
                      ]
                    : []),
                  ["사용 여부", detail.use_yn === "Y" ? "사용" : "중지"],
                ].map(([label, value]) => (
                  <div key={label}>
                    <dt>{label}</dt>
                    <dd>{value ?? "–"}</dd>
                  </div>
                ))}
              </dl>
            ) : (
              <PartnerActivityTabs key={tab} id={id} initial={tab} embedded />
            )}
          </>
        )}
        {!mobile && disable && detail && (
          <PartnerDisableDialog
            rows={[detail]}
            onCancel={() => setDisable(false)}
            onChanged={changed}
          />
        )}
      </section>
    </div>
  );
}

// SCR-UI-15. Other screen IDs in the shared inventory retain their existing owners and routes.
export default function UI15Screen({
  name = "거래처·영업 파트",
  groupName = "기준정보",
  partnerActions,
  salesPartContent,
}: {
  name?: string;
  groupName?: string;
  partnerActions?: ReactNode;
  salesPartContent?: ReactNode;
}) {
  const [tab, setTab] = useState("partners"),
    [draft, setDraft] = useState({ kind: "", useYn: "Y", q: "" }),
    [filters, setFilters] = useState(draft),
    [page, setPage] = useState(1),
    [size, setSize] = useState<20 | 50 | 100>(50),
    [sort, setSort] = useState(""),
    [selected, setSelected] = useState<string[]>([]),
    [data, setData] = useState<PartnerPage<PartnerRow> | null>(null),
    [sales, setSales] = useState<PartnerPage<SalesPartRow> | null>(null),
    [error, setError] = useState<ApiFailure | null>(null),
    [version, setVersion] = useState(0),
    [detail, setDetail] = useState<{ id: string; tab: string } | null>(null),
    [disable, setDisable] = useState(false),
    [message, setMessage] = useState("");
  const mobile = usePartnerMobile(),
    kinds = useCodes("PARTNER_KIND");
  useEffect(() => {
    let live = true;
    setError(null);
    setData(null);
    setSales(null);
    setSelected([]);
    const query = partnerQuery(tab === "partners" ? filters : {});
    query.set("page", String(page));
    query.set("size", String(size));
    if (sort) query.set("sort", sort);
    (tab === "partners"
      ? readData<PartnerPage<PartnerRow>>(`/api/partners?${query}`).then(
          (d) => {
            if (live) setData(d);
          },
        )
      : readData<PartnerPage<SalesPartRow>>(`/api/sales-parts?${query}`).then(
          (d) => {
            if (live) setSales(d);
          },
        )
    ).catch((e) => {
      if (live) setError(partnerFailure(e));
    });
    return () => {
      live = false;
    };
  }, [tab, filters, page, size, sort, version]);
  const changed = (text: string) => {
    setMessage(text);
    setVersion((v) => v + 1);
  };
  const toggle = (id: string, checked: boolean) =>
    setSelected((ids) =>
      checked ? [...ids, id] : ids.filter((x) => x !== id),
    );
  const order = (key: string) => {
    setSort((current) =>
      current === `${key},asc` ? `${key},desc` : `${key},asc`,
    );
    setPage(1);
  };
  const total = data?.total ?? sales?.total ?? 0;
  return (
    <section
      className={styles.screen}
      data-testid="partners-screen"
      data-screen-id="SCR-UI-15"
    >
      <ScreenHeading
        screenId="UI-15"
        name={name}
        groupName={groupName}
        actions={!mobile && tab === "partners" ? partnerActions : undefined}
      />
      <Tabs
        selected={tab}
        onChange={(value) => {
          setTab(value);
          setPage(1);
          setSort("");
        }}
        items={[
          { key: "partners", label: "거래처" },
          { key: "sales-parts", label: "영업 파트" },
        ]}
      />
      {message && <p role="status">{message}</p>}
      {tab === "partners" && (
        <SearchBar
          onSearch={() => {
            setFilters({ ...draft });
            setPage(1);
          }}
          onReset={() => {
            const clean = { kind: "", useYn: "Y", q: "" };
            setDraft(clean);
            setFilters(clean);
            setPage(1);
          }}
        >
          <label>
            구분
            <select
              data-testid="partner-kind-filter"
              value={draft.kind}
              onChange={(e) => setDraft({ ...draft, kind: e.target.value })}
            >
              <option value="">전체</option>
              {kinds.codes.map((k) => (
                <option key={k.code} value={k.code}>
                  {k.name}
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
              data-testid="partner-search-input"
              value={draft.q}
              placeholder="상호 또는 사업자번호"
              onChange={(e) => setDraft({ ...draft, q: e.target.value })}
            />
          </label>
        </SearchBar>
      )}
      <ErrorBanner error={error} onRetry={() => setVersion((v) => v + 1)} />
      {!error && !data && !sales && <Skeleton />}
      {tab === "sales-parts" && salesPartContent ? (
        salesPartContent
      ) : (
        <>
          <div className={styles.toolbar}>
            <span>총 {total}건</span>
            {!mobile && tab === "partners" && (
              <Button
                data-testid="partner-disable-open"
                disabled={!selected.length}
                onClick={() => setDisable(true)}
              >
                사용 중지
              </Button>
            )}
            {!mobile && (
              <PartnerExportButton
                resource={tab as "partners" | "sales-parts"}
                filters={tab === "partners" ? filters : {}}
                columns={(tab === "partners"
                  ? partnerDisplayColumns
                  : salesColumns
                ).map((c) => c[0])}
                ids={selected}
                total={total}
              />
            )}
          </div>
          <div className={styles.scroll}>
            <table className={styles.table}>
              <thead>
                <tr>
                  {!mobile && <th>선택</th>}
                  {(tab === "partners"
                    ? partnerDisplayColumns
                    : salesColumns
                  ).map(([key, label]) => (
                    <th key={key}>
                      {key === "scheduledChanges" ? (
                        label
                      ) : (
                        <button
                          data-testid={`partner-sort-${key}`}
                          onClick={() => order(key)}
                        >
                          {label}
                        </button>
                      )}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data?.items.map((row) => (
                  <tr
                    key={row.ptnr_id}
                    data-testid={`partner-row-${row.ptnr_id}`}
                  >
                    {!mobile && (
                      <td>
                        <input
                          data-testid={`partner-select-${row.ptnr_id}`}
                          type="checkbox"
                          aria-label={`${row.ptnr_nm} 선택`}
                          checked={selected.includes(row.ptnr_id)}
                          onChange={(e) =>
                            toggle(row.ptnr_id, e.target.checked)
                          }
                        />
                      </td>
                    )}
                    {partnerDisplayColumns.map(([key]) => (
                      <td key={key}>
                        {key === "ptnr_nm" ? (
                          <Button
                            data-testid={`partner-detail-${row.ptnr_id}`}
                            variant="text"
                            onClick={() =>
                              setDetail({ id: row.ptnr_id, tab: "basic" })
                            }
                          >
                            {row.ptnr_nm}
                          </Button>
                        ) : key === "ptnr_se_cd" ? (
                          (row.kindName ?? row.ptnr_se_cd)
                        ) : key === "indty_cd" ? (
                          (row.industryName ?? "–")
                        ) : key === "use_yn" ? (
                          row.use_yn === "Y" ? (
                            "사용"
                          ) : (
                            "중지"
                          )
                        ) : (key === "projectCount" ||
                            key === "assignmentPeopleCount") &&
                          Number(row[key]) > 0 ? (
                          <Button
                            data-testid={`partner-${key}-${row.ptnr_id}`}
                            variant="text"
                            onClick={() =>
                              setDetail({ id: row.ptnr_id, tab: "assignments" })
                            }
                          >
                            {row[key]}
                          </Button>
                        ) : (
                          (row[key] ?? "–")
                        )}
                      </td>
                    ))}
                  </tr>
                ))}
                {sales?.items.map((row) => (
                  <tr
                    key={row.bsn_part_id}
                    data-testid={`sales-part-row-${row.bsn_part_id}`}
                  >
                    {!mobile && (
                      <td>
                        <input
                          data-testid={`sales-part-select-${row.bsn_part_id}`}
                          type="checkbox"
                          aria-label={`${row.bsn_part_nm} 선택`}
                          checked={selected.includes(row.bsn_part_id)}
                          onChange={(e) =>
                            toggle(row.bsn_part_id, e.target.checked)
                          }
                        />
                      </td>
                    )}
                    {salesColumns.map(([key]) => (
                      <td key={key}>
                        {key === "scheduledChanges"
                          ? row.scheduledChanges.length
                            ? "예정"
                            : "–"
                          : key === "bsn_part_nm"
                            ? `${row.bsn_part_nm}${row.hq_yn === "Y" ? " (본사)" : ""}`
                            : key === "use_yn"
                              ? row.use_yn === "Y"
                                ? "사용"
                                : "중지"
                              : key === "ldr_nm" ||
                                  key === "prft_dstrb_rt" ||
                                  key === "bsn_fee_amt" ||
                                  key === "aply_bgn_ym"
                                ? (row.currentStandard?.[key] ?? "–")
                                : (row[key] ?? "–")}
                      </td>
                    ))}
                  </tr>
                ))}
                {(data || sales) && !total && (
                  <tr>
                    <td colSpan={14}>조건에 맞는 결과가 없습니다</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <Pagination
            page={page}
            size={size}
            total={total}
            onPage={setPage}
            onSize={(value) => {
              setSize(value);
              setPage(1);
            }}
          />
        </>
      )}
      {detail && (
        <PartnerDetailDrawer
          id={detail.id}
          initial={detail.tab}
          onClose={() => setDetail(null)}
          onChanged={changed}
        />
      )}
      {!mobile && disable && data && (
        <PartnerDisableDialog
          rows={data.items.filter((r) => selected.includes(r.ptnr_id))}
          onCancel={() => setDisable(false)}
          onChanged={changed}
        />
      )}
    </section>
  );
}
