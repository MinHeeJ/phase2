import { useCallback, useEffect, useRef, useState } from "react";
import { useLocation } from "react-router-dom";
import {
  Badge,
  Button,
  Pagination,
  Skeleton,
  Tabs,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import { ScreenHeading } from "../shared/ScreenHeading";
import { listPartnerOptions } from "../partnersops/client";
import { CandidateForm } from "./CandidateForm";
import { CandidateActionDialog } from "./CandidateActionDialog";
import {
  defaultCandidateFilters,
  listCandidates,
  getCandidate,
  getCandidateShortages,
  type CandidateDetail,
  type CandidateFilters,
  type CandidateShortage,
} from "./client";
import pool from "../developers/Developers.module.css";
import styles from "./Candidates.module.css";

const vendors = () => listPartnerOptions("VEND");
export function CandidateStatusBadge({
  status,
  label,
}: {
  status: string;
  label: string;
}) {
  return (
    <Badge
      tone={
        status === "INTV"
          ? "warning"
          : status === "SIGNED"
            ? "success"
            : status === "DONE"
              ? "info"
              : status === "HOLD"
                ? "locked"
                : "neutral"
      }
    >
      {label}
    </Badge>
  );
}
export default function UI33Screen({
  name = "외부 인력 후보",
  groupName = "인력 운영",
  onDirtyChange,
}: {
  name?: string;
  groupName?: string;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const location = useLocation();
  const incoming = (
    location.state as { candidateFilters?: Partial<CandidateFilters> } | null
  )?.candidateFilters;
  const lastIncoming = useRef(incoming);
  const [filters, setFilters] = useState<CandidateFilters>(() => ({
    ...defaultCandidateFilters,
    ...incoming,
  }));
  const [draft, setDraft] = useState(filters),
    [editor, setEditor] = useState<CandidateDetail | "new" | null>(null);
  const [detailId, setDetailId] = useState<number | null>(null),
    [message, setMessage] = useState("");
  const jobs = useCodes("JOB"),
    grades = useCodes("GRADE"),
    skills = useCodes("SKILL"),
    types = useCodes("EMP_TYPE"),
    statuses = useCodes("CONTACT_STATUS"),
    sources = useCodes("CANDIDATE_SOURCE"),
    partners = useAdminRead(vendors);
  const read = useCallback(() => listCandidates(filters), [filters]),
    list = useAdminRead(read),
    shortages = useAdminRead(getCandidateShortages);
  useEffect(() => {
    if (
      location.pathname === "/UI-33" &&
      incoming &&
      incoming !== lastIncoming.current
    ) {
      lastIncoming.current = incoming;
      const f = { ...defaultCandidateFilters, ...incoming };
      setFilters(f);
      setDraft(f);
    }
  }, [location.pathname, incoming]);
  const label = (group: string, code: string | null) => {
    const options = {
      JOB: jobs,
      GRADE: grades,
      SKILL: skills,
      EMP_TYPE: types,
      CONTACT_STATUS: statuses,
      CANDIDATE_SOURCE: sources,
    };
    return code
      ? (options[group as keyof typeof options]?.codes.find(
          (c) => c.code === code,
        )?.name ?? code)
      : "–";
  };
  const apply = (f: CandidateFilters) => {
    setFilters({ ...f, page: 0 });
    setDraft({ ...f, page: 0 });
  };
  const selectShortage = (s: CandidateShortage) =>
    apply({
      ...filters,
      job: s.job_cd,
      grade: s.grd_cd ?? "",
      shortage_month: s.first_shortage_month,
    });
  const saved = async (row: CandidateDetail) => {
    await getCandidate(row.cand_id);
    await list.reload();
    setEditor(null);
    setDetailId(row.cand_id);
    setMessage("저장했습니다.");
  };
  const options = [
    { key: "job", text: "직무", rows: jobs.codes },
    { key: "grade", text: "등급", rows: grades.codes },
    { key: "skill", text: "스킬", rows: skills.codes },
    {
      key: "cand_se_cd",
      text: "구분",
      rows: types.codes.filter((c) => ["FL", "OS"].includes(c.code)),
    },
    { key: "cntct_stts_cd", text: "상태", rows: statuses.codes },
  ] as const;
  const columns = [
    ["cand_no", "후보번호"],
    ["cand_nm", "이름"],
    ["cand_se_cd", "구분"],
    ["vndr_ptnr_id", "업체"],
    ["job_cd", "직무"],
    ["grd_cd", "등급"],
    ["skill", "스킬"],
    ["hope_untprc_amt", "희망단가"],
    ["avail_dt", "가능일"],
    ["cntct_stts_cd", "상태"],
    ["last_cntct_dt", "최근 연락일"],
    ["source", "출처"],
  ] as const;
  return (
    <section data-testid="candidate-workspace" data-screen-id="SCR-UI-33">
      <ScreenHeading
        screenId="UI-33"
        name={name}
        groupName={groupName}
        actions={
          <Button
            data-testid="candidate-create"
            variant="primary"
            onClick={() => setEditor("new")}
          >
            + 후보 등록
          </Button>
        }
      />
      {message && <p role="status">{message}</p>}
      {editor ? (
        <CandidateForm
          key={editor === "new" ? "new" : editor.cand_id}
          initial={editor === "new" ? undefined : editor}
          onCancel={() => setEditor(null)}
          onSaved={saved}
          onDirtyChange={onDirtyChange}
        />
      ) : (
        <>
          {list.data && (
            <div className={styles.kpi}>
              {(
                [
                  ["후보 수", list.data.kpi.candidate_count],
                  ["면접 예정", list.data.kpi.interview_count],
                  [
                    "다음 달까지 가용",
                    list.data.kpi.available_by_next_month_count,
                  ],
                  [
                    "30일 이상 미연락",
                    list.data.kpi.uncontacted_over_30_days_count,
                  ],
                ] as const
              ).map(([title, count]) => (
                <div key={title} className={pool.card}>
                  <span>{title}</span>
                  <strong>{count.toLocaleString("ko-KR")}</strong>
                  {title === "30일 이상 미연락" && (
                    <small>30일 초과 기준</small>
                  )}
                </div>
              ))}
            </div>
          )}
          <section className={pool.card} data-testid="candidate-shortcuts">
            <h2>부족 직무와 맞는 후보</h2>
            <ErrorBanner
              error={shortages.error}
              onRetry={() => void shortages.reload().catch(() => {})}
            />
            {shortages.loading ? (
              <Skeleton label="부족 직무 불러오는 중" />
            ) : shortages.data?.items.length === 0 ? (
              <p>부족 직무가 없습니다.</p>
            ) : (
              shortages.data?.items.map((s, i) => (
                <Button
                  key={`${s.job_cd}-${s.grd_cd}-${s.first_shortage_month}`}
                  data-testid={`candidate-shortcut-${i}`}
                  onClick={() => selectShortage(s)}
                >
                  {label("JOB", s.job_cd)} · {label("GRADE", s.grd_cd ?? null)}{" "}
                  · {s.first_shortage_month} · 후보 {s.candidate_count}명
                </Button>
              ))
            )}
          </section>
          <form
            className={pool.search}
            data-testid="candidate-search"
            onSubmit={(e) => {
              e.preventDefault();
              apply(draft);
            }}
          >
            {options.map((o) => (
              <label key={o.key}>
                {o.text}
                <select
                  data-testid={`candidate-filter-${o.key.replaceAll("_", "-")}`}
                  aria-label={`조회 ${o.text}`}
                  value={draft[o.key]}
                  onChange={(e) =>
                    setDraft((d) => ({ ...d, [o.key]: e.target.value }))
                  }
                >
                  <option value="">전체</option>
                  {o.rows.map((c) => (
                    <option key={c.code} value={c.code}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </label>
            ))}
            <label>
              업체
              <select
                data-testid="candidate-filter-vendor"
                value={draft.vndr_ptnr_id}
                onChange={(e) =>
                  setDraft((d) => ({ ...d, vndr_ptnr_id: e.target.value }))
                }
              >
                <option value="">전체</option>
                {partners.data?.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              가능일 이전
              <input
                data-testid="candidate-filter-date"
                type="date"
                value={draft.avail_dt}
                onChange={(e) =>
                  setDraft((d) => ({ ...d, avail_dt: e.target.value }))
                }
              />
            </label>
            <label>
              부족월
              <input
                data-testid="candidate-filter-month"
                type="month"
                value={draft.shortage_month}
                onChange={(e) =>
                  setDraft((d) => ({ ...d, shortage_month: e.target.value }))
                }
              />
            </label>
            <label>
              <input
                data-testid="candidate-filter-uncontacted"
                type="checkbox"
                checked={draft.uncontacted_only}
                onChange={(e) =>
                  setDraft((d) => ({
                    ...d,
                    uncontacted_only: e.target.checked,
                  }))
                }
              />
              30일 초과 미연락만
            </label>
            <label>
              후보번호·이름
              <input
                data-testid="candidate-filter-name"
                value={draft.q}
                onChange={(e) => setDraft((d) => ({ ...d, q: e.target.value }))}
              />
            </label>
            <Button
              data-testid="candidate-reset"
              type="button"
              onClick={() => apply({ ...defaultCandidateFilters })}
            >
              초기화
            </Button>
            <Button
              data-testid="candidate-query"
              type="submit"
              variant="primary"
            >
              조회
            </Button>
          </form>
          {[jobs, grades, skills, types, statuses, sources].map((g, i) => (
            <ErrorBanner key={i} error={g.error} onRetry={g.retry} />
          ))}
          <ErrorBanner
            error={partners.error}
            onRetry={() => void partners.reload().catch(() => {})}
          />
          <ErrorBanner
            error={list.error}
            onRetry={() => void list.reload().catch(() => {})}
          />
          {list.loading ? (
            <Skeleton />
          ) : (
            !list.error &&
            list.data && (
              <>
                <p>총 {list.data.total}건</p>
                <div className={pool.tableScroll}>
                  <table className={pool.table}>
                    <thead>
                      <tr>
                        {columns.map(([key, title]) => (
                          <th key={key}>
                            {key === "skill" ? (
                              title
                            ) : (
                              <button
                                data-testid={`candidate-sort-${key.replaceAll("_", "-")}`}
                                onClick={() =>
                                  setFilters((f) => ({
                                    ...f,
                                    sort: `${key},${f.sort === `${key},asc` ? "desc" : "asc"}`,
                                    page: 0,
                                  }))
                                }
                              >
                                {title}
                              </button>
                            )}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {list.data.items.length === 0 && (
                        <tr>
                          <td colSpan={columns.length}>
                            조건에 맞는 결과가 없습니다.{" "}
                            <Button
                              data-testid="candidate-empty-reset"
                              onClick={() =>
                                apply({ ...defaultCandidateFilters })
                              }
                            >
                              조건 초기화
                            </Button>
                          </td>
                        </tr>
                      )}
                      {list.data.items.map((row) => (
                        <tr
                          data-testid={`candidate-row-${row.cand_id}`}
                          key={row.cand_id}
                          tabIndex={0}
                          onClick={() => setDetailId(row.cand_id)}
                          onKeyDown={(e) => {
                            if (e.key === "Enter") setDetailId(row.cand_id);
                          }}
                        >
                          <td>{row.cand_no}</td>
                          <td>{row.cand_nm}</td>
                          <td>{label("EMP_TYPE", row.cand_se_cd)}</td>
                          <td>
                            {partners.data?.find(
                              (p) => Number(p.id) === row.vndr_ptnr_id,
                            )?.name ?? "–"}
                          </td>
                          <td>{label("JOB", row.job_cd)}</td>
                          <td>{label("GRADE", row.grd_cd)}</td>
                          <td>
                            {row.skill
                              .map((s) => label("SKILL", s))
                              .join(", ") || "–"}
                          </td>
                          <td className={pool.amount}>
                            {row.hope_untprc_amt?.toLocaleString("ko-KR") ??
                              "–"}
                          </td>
                          <td>{row.avail_dt}</td>
                          <td>
                            <CandidateStatusBadge
                              status={row.cntct_stts_cd}
                              label={label("CONTACT_STATUS", row.cntct_stts_cd)}
                            />
                          </td>
                          <td>{row.last_cntct_dt ?? "–"}</td>
                          <td>{label("CANDIDATE_SOURCE", row.source)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <Pagination
                  page={filters.page + 1}
                  size={filters.size}
                  total={list.data.total}
                  onPage={(page) =>
                    setFilters((f) => ({ ...f, page: page - 1 }))
                  }
                  onSize={(size) =>
                    setFilters((f) => ({ ...f, size, page: 0 }))
                  }
                />
              </>
            )
          )}
        </>
      )}
      {detailId != null && !editor && (
        <CandidateDrawer
          key={detailId}
          id={detailId}
          onClose={() => setDetailId(null)}
          onEdit={(row) => setEditor(row)}
          label={label}
          onSaved={async () => {
            await list.reload();
            setMessage("저장했습니다.");
          }}
        />
      )}
    </section>
  );
}

function CandidateDrawer({
  id,
  onClose,
  onEdit,
  onSaved,
  label,
}: {
  id: number;
  onClose: () => void;
  onEdit: (row: CandidateDetail) => void;
  onSaved: () => Promise<unknown>;
  label: (group: string, code: string | null) => string;
}) {
  const read = useCallback(() => getCandidate(id), [id]),
    detail = useAdminRead(read);
  const [tab, setTab] = useState("basic"),
    [action, setAction] = useState<"contact" | "convert" | null>(null);
  const refresh = async () => {
    await detail.reload();
    await onSaved();
  };
  const row = detail.data;
  return (
    <div className={pool.scrim}>
      <aside
        data-testid="candidate-drawer"
        className={pool.drawer}
        role="dialog"
        aria-modal="true"
        aria-label="후보 상세"
      >
        <div className={pool.drawerHeader}>
          <h2>후보 상세</h2>
          <Button data-testid="candidate-detail-close" onClick={onClose}>
            닫기
          </Button>
        </div>
        <ErrorBanner
          error={detail.error}
          onRetry={() => void detail.reload().catch(() => {})}
        />
        {detail.loading ? (
          <Skeleton />
        ) : (
          row && (
            <>
              <h3>
                {row.cand_nm} · {row.cand_no}
              </h3>
              <CandidateStatusBadge
                status={row.cntct_stts_cd}
                label={label("CONTACT_STATUS", row.cntct_stts_cd)}
              />
              <div className={pool.actions}>
                <Button
                  data-testid="candidate-edit"
                  onClick={() => onEdit(row)}
                >
                  수정
                </Button>
                <Button
                  data-testid="candidate-contact-open"
                  onClick={() => setAction("contact")}
                >
                  연락 기록
                </Button>
                <Button
                  data-testid="candidate-convert-open"
                  disabled={row.dev_id != null}
                  onClick={() => setAction("convert")}
                >
                  개발자로 등록
                </Button>
              </div>
              {row.dev_id != null && (
                <p>개발자 등록 완료 · 개발자 ID {row.dev_id}</p>
              )}
              <Tabs
                items={[
                  { key: "basic", label: "기본 정보" },
                  { key: "matching", label: "맞는 자리" },
                  { key: "contacts", label: "연락 기록" },
                ]}
                selected={tab}
                onChange={setTab}
              />
              <section
                data-testid="candidate-detail-panel"
                role="tabpanel"
                className={styles.detail}
              >
                {tab === "basic" && (
                  <dl>
                    {(
                      [
                        ["구분", label("EMP_TYPE", row.cand_se_cd)],
                        ["직무", label("JOB", row.job_cd)],
                        ["등급", label("GRADE", row.grd_cd)],
                        [
                          "스킬",
                          row.skill.map((c) => label("SKILL", c)).join(", ") ||
                            "–",
                        ],
                        [
                          "희망단가",
                          row.hope_untprc_amt?.toLocaleString("ko-KR") ?? "–",
                        ],
                        ["가능일", row.avail_dt],
                        ["최근 연락일", row.last_cntct_dt ?? "–"],
                        ["연락처", row.tel_no ?? "–"],
                        ["출처", label("CANDIDATE_SOURCE", row.source)],
                        ["메모", row.rmrk_desc ?? "–"],
                      ] as const
                    ).map(([k, v]) => (
                      <div key={k}>
                        <dt>{k}</dt>
                        <dd data-sensitive={k === "연락처" || undefined}>
                          {v}
                        </dd>
                      </div>
                    ))}
                  </dl>
                )}
                {tab === "matching" && (
                  <>
                    {row.matching_slots.length === 0 ? (
                      <p>맞는 자리가 없습니다.</p>
                    ) : (
                      <div className={pool.tableScroll}>
                        <table className={pool.table}>
                          <thead>
                            <tr>
                              <th>기회명</th>
                              <th>직무</th>
                              <th>시작월</th>
                              <th>월 단가</th>
                              <th>팀비 경비</th>
                              <th>예상이익률</th>
                            </tr>
                          </thead>
                          <tbody>
                            {row.matching_slots.map((s) => (
                              <tr
                                key={s.need_id}
                                data-testid={`candidate-slot-${s.need_id}`}
                              >
                                <td>
                                  {s.oppt_nm}
                                  {s.warnings.map((w) => (
                                    <p key={w}>{w}</p>
                                  ))}
                                </td>
                                <td>{label("JOB", s.job_cd)}</td>
                                <td>{s.bgn_ym}</td>
                                <td>
                                  {s.monthly_price.toLocaleString("ko-KR")}
                                </td>
                                <td>
                                  {s.team_expense.toLocaleString("ko-KR")}
                                </td>
                                <td>
                                  {s.margin_rate == null
                                    ? "–"
                                    : `${s.margin_rate.toFixed(2)}%`}{" "}
                                  {s.estimated && <Badge>추정</Badge>}
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                    <p>후보 상태에서는 가배정하지 않습니다.</p>
                  </>
                )}
                {tab === "contacts" && (
                  <>
                    {row.contacts.length === 0 ? (
                      <p>연락 기록이 없습니다.</p>
                    ) : (
                      <div className={pool.tableScroll}>
                        <table className={pool.table}>
                          <thead>
                            <tr>
                              <th>날짜</th>
                              <th>상태</th>
                              <th>메모</th>
                              <th>기록자</th>
                            </tr>
                          </thead>
                          <tbody>
                            {row.contacts.map((h, i) => (
                              <tr
                                key={`${h.created_at}-${i}`}
                                data-testid={`candidate-contact-${i}`}
                              >
                                <td>{h.cntct_dt}</td>
                                <td>
                                  {label("CONTACT_STATUS", h.cntct_stts_cd)}
                                </td>
                                <td>{h.memo}</td>
                                <td>{h.user_id ?? "–"}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    )}
                  </>
                )}
              </section>
              {action && (
                <CandidateActionDialog
                  row={row}
                  action={action}
                  onClose={() => setAction(null)}
                  onSaved={refresh}
                />
              )}
            </>
          )
        )}
      </aside>
    </div>
  );
}
