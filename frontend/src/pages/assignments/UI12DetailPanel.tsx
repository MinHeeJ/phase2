import { useCallback, type ReactNode } from "react";
import { Button, Badge, Skeleton, LockedBanner } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import {
  getAssignment,
  type AssignmentDetail,
  type AssignmentWarning,
} from "./client";
import styles from "./Assignments.module.css";

export function AssignmentWarnings({
  warnings,
}: {
  warnings: AssignmentWarning[];
}) {
  return warnings.length ? (
    <div
      className={styles.warning}
      role="status"
      data-testid="assignment-warnings"
    >
      {warnings.map((w) => (
        <span
          data-testid={`assignment-warning-${warnings.indexOf(w)}`}
          key={`${w.code}-${w.work_ym ?? ""}`}
        >
          {w.message}
        </span>
      ))}
    </div>
  ) : null;
}
export function UI12DetailPanel({
  id,
  onClose,
  onProject,
  onDeveloper,
  children,
  actions,
  refreshVersion,
}: {
  id: number;
  onClose: () => void;
  onProject?: (id: number) => void;
  onDeveloper?: (id: number) => void;
  children?: (row: AssignmentDetail) => ReactNode;
  actions?: (row: AssignmentDetail) => ReactNode;
  refreshVersion?: string;
}) {
  const state = useAdminRead(
    useCallback(() => getAssignment(id), [id, refreshVersion]),
  );
  const jobs = useCodes("JOB"),
    kinds = useCodes("ASSIGN_KIND"),
    chances = useCodes("EXT_POSSIBILITY");
  const row = state.data;
  const label = (codes: typeof jobs, code: string | null) =>
    codes.codes.find((c) => c.code === code)?.name ?? code ?? "미정";
  return (
    <div data-testid="assignment-detail-root" className={styles.scrim}>
      <aside
        className={styles.drawer}
        role="dialog"
        aria-modal="true"
        aria-label="투입 상세"
        data-testid="assignment-detail"
      >
        <header>
          <h2>{row ? `${row.dev_nm} · ${row.prj_nm}` : "투입 상세"}</h2>
          <Button data-testid="assignment-detail-close" onClick={onClose}>
            닫기
          </Button>
        </header>
        <ErrorBanner
          error={state.error}
          onRetry={() => void state.reload().catch(() => {})}
        />
        {state.loading ? (
          <Skeleton />
        ) : (
          row &&
          !state.error && (
            <>
              <p>
                {row.asgn_no} · {label(jobs, row.job_cd)} · {row.bgn_dt} ~{" "}
                {row.end_dt}
              </p>
              <AssignmentWarnings warnings={row.warnings} />
              {(row.asgn_stts_cd === "CANCELLED" ||
                row.asgn_stts_cd === "ENDED") && (
                <LockedBanner>읽기 전용 · {row.asgn_stts_cd}</LockedBanner>
              )}
              {row.asgn_stts_cd !== "CANCELLED" &&
                row.asgn_stts_cd !== "ENDED" &&
                actions?.(row)}
              <h3>투입 정보</h3>
              <dl className={styles.details}>
                <dt>투입ID</dt>
                <dd>{row.asgn_no}</dd>
                <dt>프로젝트</dt>
                <dd>
                  <Button
                    variant="text"
                    data-testid="assignment-detail-project"
                    onClick={() => onProject?.(row.prj_id)}
                  >
                    {row.prj_nm}
                  </Button>
                </dd>
                <dt>개발자</dt>
                <dd>
                  <Button
                    variant="text"
                    data-testid="assignment-detail-developer"
                    onClick={() => onDeveloper?.(row.dev_id)}
                  >
                    {row.dev_nm} · {row.mng_no}
                  </Button>
                </dd>
                <dt>직무</dt>
                <dd>{label(jobs, row.job_cd)}</dd>
                <dt>투입일</dt>
                <dd>{row.bgn_dt}</dd>
                <dt>철수일</dt>
                <dd>{row.end_dt}</dd>
                {row.plan_end_dt !== row.end_dt && (
                  <>
                    <dt>처음 계획</dt>
                    <dd>{row.plan_end_dt}</dd>
                  </>
                )}
                <dt>투입률</dt>
                <dd>{row.asgn_rt.toFixed(2)}</dd>
                <dt>오늘 단가</dt>
                <dd>
                  {row.bill_untprc_amt == null
                    ? "배분표 / 단가 없음"
                    : `${row.bill_untprc_amt.toLocaleString("ko-KR")}원`}
                </dd>
                <dt>단가 기준</dt>
                <dd>{row.untprc_base_cd ?? "–"}</dd>
                <dt>투입 구분</dt>
                <dd>{label(kinds, row.asgn_se_cd)}</dd>
                {row.asgn_se_cd === "SUB" && (
                  <>
                    <dt>계약상 인력</dt>
                    <dd>{row.cntrct_dev_nm}</dd>
                  </>
                )}
                <dt>연장 가능성</dt>
                <dd>{label(chances, row.ext_psblty_cd)}</dd>
                <dt>연장 횟수</dt>
                <dd>{row.extend_count}</dd>
                <dt>상태</dt>
                <dd>
                  <Badge>{row.asgn_stts_cd}</Badge>
                </dd>
                <dt>비고</dt>
                <dd>{row.rmrk_desc ?? "–"}</dd>
              </dl>
              <h3>단가 이력</h3>
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>적용 시작일</th>
                    <th>단가</th>
                  </tr>
                </thead>
                <tbody>
                  {row.rates.map((r) => (
                    <tr
                      key={r.aply_bgn_dt}
                      data-testid={`assignment-rate-${r.aply_bgn_dt}`}
                    >
                      <td>{r.aply_bgn_dt}</td>
                      <td>
                        {r.bill_untprc_amt?.toLocaleString("ko-KR") ?? "–"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <h3>월별 투입 상세</h3>
              <div className={styles.tableScroll}>
                <table className={styles.table}>
                  <thead>
                    <tr>
                      {[
                        "월",
                        "자동 MM",
                        "MM",
                        "수정함",
                        "그 사람 그 달 합계",
                        "마감",
                      ].map((c) => (
                        <th
                          data-testid={`assignment-detail-month-header-${["월", "자동 MM", "MM", "수정함", "그 사람 그 달 합계", "마감"].indexOf(c)}`}
                          key={c}
                        >
                          {c}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {row.months.map((m) => (
                      <tr
                        key={m.work_ym}
                        data-testid={`assignment-month-${m.work_ym}`}
                      >
                        <td>{m.work_ym}</td>
                        <td>{m.auto_mm_qty.toFixed(2)}</td>
                        <td>{m.mm_qty.toFixed(2)}</td>
                        <td>
                          {m.mnl_yn === "Y" ? (
                            <Badge tone="info">수정함</Badge>
                          ) : (
                            "–"
                          )}
                        </td>
                        <td>
                          <Badge
                            tone={
                              m.person_month_mm_qty > 1 ? "warning" : "neutral"
                            }
                          >
                            {m.person_month_mm_qty.toFixed(2)}
                          </Badge>
                        </td>
                        <td>
                          {m.closed_yn === "Y" ? (
                            <Badge tone="locked">마감</Badge>
                          ) : (
                            "미마감"
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {row.months.length === 0 && (
                <p className={styles.empty}>등록된 월별 MM이 없습니다.</p>
              )}
              {children?.(row)}
            </>
          )
        )}
      </aside>
    </div>
  );
}
