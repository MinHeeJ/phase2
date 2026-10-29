import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Badge, Button, Pagination, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { Can } from "../../shared/Can";
import { useAdminRead } from "../admin/useAdminResource";
import { DeveloperDrawer } from "../developers/DeveloperDrawer";
import { listProjectOptions } from "../projects/client";
import { useCodes } from "../shared/useCodes";
import {
  downloadCostRenegotiations,
  type CostRenegotiationFilters,
} from "../costprofit/client";
import {
  readRenegotiations,
  renegotiationDefaults,
  type RenegotiationRow,
} from "./client";
import tables from "../developers/Developers.module.css";
import shared from "../shared/SharedScreens.module.css";
import styles from "./Renegotiations.module.css";

const money = (value: number | null | undefined) =>
  value == null ? "–" : value.toLocaleString("ko-KR");
const ratio = (value: number | null | undefined) =>
  value == null ? "–" : `${value.toFixed(1)}%`;
// Logical UI-32 renegotiation feature. Intentionally unregistered: /UI-32 remains developer registration.
export default function UI32RenegotiationsScreen() {
  const navigate = useNavigate();
  const [filters, setFilters] = useState<CostRenegotiationFilters>({
    ...renegotiationDefaults,
  });
  const [draft, setDraft] = useState(filters);
  const [rate, setRate] = useState("5");
  const [validation, setValidation] = useState("");
  const [developer, setDeveloper] = useState<number | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<ApiFailure | null>(null);
  const list = useAdminRead(
    useCallback(() => readRenegotiations(filters), [filters]),
  );
  const projects = useAdminRead(listProjectOptions);
  const employees = useCodes("EMP_TYPE");
  const reset = () => {
    setRate("5");
    setValidation("");
    setDraft({ ...renegotiationDefaults });
    setFilters({ ...renegotiationDefaults });
  };
  const changeRate = (value: string) => {
    setRate(value);
    if (
      value.trim() === "" ||
      !Number.isFinite(Number(value)) ||
      Number(value) < 0 ||
      Number(value) > 50
    ) {
      setValidation("인상률은 0~50%로 입력하세요.");
      return;
    }
    setValidation("");
    setFilters((f) => ({ ...f, raiseRate: Number(value), page: 0 }));
  };
  const download = async () => {
    setDownloading(true);
    setDownloadError(null);
    try {
      const file = await downloadCostRenegotiations(filters);
      const url = URL.createObjectURL(file.blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = file.filename;
      link.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setDownloadError(
        e instanceof ApiFailure
          ? e
          : new ApiFailure("다운로드에 실패했습니다.", null, null),
      );
    } finally {
      setDownloading(false);
    }
  };
  const sort = (key: string) =>
    setFilters((f) => ({
      ...f,
      page: 0,
      sort: `${key},${f.sort === `${key},asc` ? "desc" : "asc"}`,
    }));
  const columns: [string, string][] = [
    ["dev_nm", "이름"],
    ["emp_se_cd", "구분"],
    ["prj_nm", "프로젝트"],
    ["end_dt", "갱신일"],
    ["mm_qty", "MM"],
    ["bill_untprc_amt", "현재 단가"],
    ["monthly_cost_amt", "월 원가"],
    ["margin_rt", "이익률"],
    ["target_price_amt", "목표 단가 (20%)"],
    ["required_raise_rt", "필요 인상률"],
    ["annual_profit_increase_amt", `${filters.raiseRate}% 올리면 연 이익`],
  ];
  const data = list.data;
  const employeeName = (code: string) =>
    employees.codes.find((c) => c.code === code)?.name ?? code;
  const openDeveloper = (row: RenegotiationRow) => setDeveloper(row.dev_id);
  return (
    <section data-testid="renegotiations-screen">
      <div className={shared.breadcrumb}>원가·수익 › 단가 재협상 대상</div>
      <header className={shared.title}>
        <h1>단가 재협상 대상</h1>
        <span>화면 경로 승인 대기</span>
        <div className={shared.headerActions}>
          <Can perm="UI-32.download">
            <Button
              data-testid="renegotiations-download"
              pending={downloading}
              disabled={!!validation || list.loading || !!list.error}
              onClick={() => void download()}
            >
              엑셀 다운로드
            </Button>
          </Can>
        </div>
      </header>
      <p className={styles.notice}>
        12개월 유지 가정 · 실제 계약 보장 아님 · 조회와 가정 계산은 저장하지
        않습니다.
      </p>
      <div className={styles.cards}>
        {[
          ["대상 인원", data ? `${data.summary.people_count}명` : "–"],
          ["매출가중 평균 이익률", ratio(data?.summary.margin_rt)],
          [
            "가정 인상 연 증가",
            money(data?.summary.annual_profit_increase_amt),
          ],
          [
            "목표 단가 연 증가",
            money(data?.summary.target_annual_profit_increase_amt),
          ],
        ].map(([label, value]) => (
          <div
            className={tables.card}
            key={label}
            data-testid={`renegotiations-summary-${label === "대상 인원" ? "people" : label === "매출가중 평균 이익률" ? "margin" : label === "가정 인상 연 증가" ? "assumed" : "target"}`}
          >
            <span>{label}</span>
            <strong>{value}</strong>
          </div>
        ))}
      </div>
      <div className={tables.toolbar}>
        <p>
          기준월 {data?.referenceMonth ?? "–"} · 단위 원{" "}
          <Badge tone={data?.status === "CLOSED" ? "locked" : "info"}>
            {data?.status === "CLOSED" ? "마감" : "미마감"}
          </Badge>
        </p>
        <label>
          인상률 %{" "}
          <input
            data-testid="renegotiations-raise-rate"
            type="number"
            min="0"
            max="50"
            step="any"
            value={rate}
            onChange={(e) => changeRate(e.target.value)}
            aria-invalid={!!validation}
            aria-describedby="renegotiations-rate-error"
          />
        </label>
      </div>
      {validation && (
        <p role="alert" id="renegotiations-rate-error">
          {validation}
        </p>
      )}
      <form
        data-testid="renegotiations-search-form"
        className={tables.search}
        onSubmit={(e) => {
          e.preventDefault();
          if (!validation)
            setFilters({ ...draft, raiseRate: Number(rate), page: 0 });
        }}
      >
        <label>
          갱신 시점{" "}
          <select
            data-testid="renegotiations-window"
            value={draft.windowMonths}
            onChange={(e) =>
              setDraft({
                ...draft,
                windowMonths: Number(e.target.value) as 3 | 6 | 12,
              })
            }
          >
            <option value="3">3개월 안</option>
            <option value="6">6개월 안</option>
            <option value="12">1년 안</option>
          </select>
        </label>
        <label>
          이익률{" "}
          <select
            data-testid="renegotiations-margin"
            value={draft.marginBelow}
            onChange={(e) =>
              setDraft({
                ...draft,
                marginBelow: Number(e.target.value) as 10 | 15 | 20,
              })
            }
          >
            <option value="10">10% 미만</option>
            <option value="15">15% 미만</option>
            <option value="20">20% 미만</option>
          </select>
        </label>
        <label>
          직원 구분{" "}
          <select
            data-testid="renegotiations-employee"
            value={draft.empType ?? ""}
            onChange={(e) =>
              setDraft({ ...draft, empType: e.target.value || undefined })
            }
          >
            <option value="">전체</option>
            {employees.codes.map((c) => (
              <option
                key={c.code}
                value={c.code}
                data-testid={`renegotiations-employee-option-${c.code.toLowerCase().replaceAll("_", "-")}`}
              >
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          프로젝트{" "}
          <select
            data-testid="renegotiations-project"
            value={draft.projectId ?? ""}
            onChange={(e) =>
              setDraft({ ...draft, projectId: e.target.value || undefined })
            }
          >
            <option value="">전체</option>
            {projects.data
              ?.filter((p) => p.type === "MO" || p.type === "DP")
              .map((p) => (
                <option
                  key={p.id}
                  value={p.id}
                  data-testid={`renegotiations-project-option-${p.id}`}
                >
                  {p.name}
                </option>
              ))}
          </select>
        </label>
        <Button
          data-testid="renegotiations-reset"
          type="button"
          onClick={reset}
        >
          초기화
        </Button>
        <Button
          data-testid="renegotiations-search"
          type="submit"
          variant="primary"
          disabled={!!validation}
        >
          조회
        </Button>
      </form>
      <ErrorBanner error={employees.error} onRetry={employees.retry} />
      <ErrorBanner
        error={projects.error}
        onRetry={() => void projects.reload().catch(() => {})}
      />
      <ErrorBanner
        error={list.error}
        onRetry={() => void list.reload().catch(() => {})}
      />
      <ErrorBanner error={downloadError} onRetry={() => void download()} />
      {list.error?.field && <p role="alert">입력 항목: {list.error.field}</p>}
      {data?.warnings.map((warning, i) => (
        <p
          className={styles.warning}
          key={`${warning}-${i}`}
          data-testid={`renegotiations-warning-${i}`}
        >
          {warning}
        </p>
      ))}
      {list.loading ? (
        <Skeleton />
      ) : (
        !list.error &&
        data && (
          <>
            <p>전체 {data.total}건 · 대상 인원은 중복 제외</p>
            <div className={tables.tableScroll}>
              <table className={tables.table} aria-label="단가 재협상 대상">
                <thead>
                  <tr>
                    {columns.map(([key, label]) => (
                      <th
                        key={key}
                        data-testid={`renegotiations-column-${key.replaceAll("_", "-")}`}
                        aria-sort={
                          filters.sort === `${key},asc`
                            ? "ascending"
                            : filters.sort === `${key},desc`
                              ? "descending"
                              : "none"
                        }
                      >
                        <button
                          data-testid={`renegotiations-sort-${key.replaceAll("_", "-")}`}
                          type="button"
                          onClick={() => sort(key)}
                        >
                          {label}
                        </button>
                      </th>
                    ))}
                    <th>후속 조치</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((row) => (
                    <tr
                      key={row.asgn_id}
                      data-testid={`renegotiations-row-${row.asgn_id}`}
                    >
                      <td>
                        <button
                          data-testid={`renegotiations-developer-${row.asgn_id}`}
                          className={tables.textButton}
                          onClick={() => openDeveloper(row)}
                        >
                          {row.dev_nm}
                        </button>
                      </td>
                      <td>
                        <Badge>{employeeName(row.emp_se_cd)}</Badge>
                      </td>
                      <td>{row.prj_nm}</td>
                      <td>
                        {row.end_dt}{" "}
                        <Badge
                          tone={
                            row.days_until_end <= 30
                              ? "danger"
                              : row.days_until_end <= 60
                                ? "warning"
                                : "neutral"
                          }
                        >
                          D-{row.days_until_end}
                        </Badge>
                      </td>
                      <td className={tables.amount}>
                        {row.mm_qty?.toFixed(2) ?? "–"}
                      </td>
                      <td className={tables.amount}>
                        {money(row.bill_untprc_amt)}
                      </td>
                      <td className={tables.amount}>
                        {money(row.monthly_cost_amt)}
                      </td>
                      <td className={tables.amount}>{ratio(row.margin_rt)}</td>
                      <td className={tables.amount}>
                        {money(row.target_price_amt)}
                      </td>
                      <td className={tables.amount}>
                        {ratio(row.required_raise_rt)}
                      </td>
                      <td className={tables.amount}>
                        {money(row.annual_profit_increase_amt)}
                      </td>
                      <td>
                        <Button
                          data-testid={`renegotiations-price-change-${row.asgn_id}`}
                          onClick={() =>
                            navigate("/UI-12", {
                              state: { assignmentId: row.asgn_id },
                            })
                          }
                        >
                          단가 변경
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {!data.items.length && (
                <div className={tables.empty}>
                  <h2>조회 결과가 없습니다.</h2>
                  <p>조회 조건을 변경해 보세요.</p>
                </div>
              )}
            </div>
            <Pagination
              page={(filters.page ?? 0) + 1}
              size={filters.size ?? 50}
              total={data.total}
              onPage={(page) => setFilters((f) => ({ ...f, page: page - 1 }))}
              onSize={(size) => setFilters((f) => ({ ...f, size, page: 0 }))}
            />
          </>
        )
      )}
      <details className={tables.card}>
        <summary data-testid="renegotiations-calculation-summary">
          계산 방법
        </summary>
        <div className={tables.tableScroll}>
          <table className={tables.table} aria-label="재협상 계산 방법">
            <thead>
              <tr>
                <th>항목</th>
                <th>계산식</th>
                <th>참고</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>갱신일</td>
                <td>투입 배정의 종료일</td>
                <td>유상 MO/DP 활성 투입</td>
              </tr>
              <tr>
                <td>월 원가</td>
                <td>배분 인건비 + 배분 경비</td>
                <td>공통 월별 배분 · 마감은 스냅샷</td>
              </tr>
              <tr>
                <td>이익률</td>
                <td>(현재 단가 × MM − 월 원가) ÷ (현재 단가 × MM)</td>
                <td>0분모·결측은 –</td>
              </tr>
              <tr>
                <td>목표 단가</td>
                <td>월 원가 ÷ MM ÷ 0.8</td>
                <td>표시만 만 원 단위 올림</td>
              </tr>
              <tr>
                <td>필요 인상률</td>
                <td>(원목표 단가 − 현재 단가) ÷ 현재 단가</td>
                <td>목표 이익률 20%</td>
              </tr>
              <tr>
                <td>연 이익 증가</td>
                <td>현재 단가 × MM × 인상률 × 12개월</td>
                <td>저장 없는 가정 · 목표 효과는 올림 전 단가 사용</td>
              </tr>
            </tbody>
          </table>
        </div>
      </details>
      {developer != null && (
        <DeveloperDrawer
          id={developer}
          onClose={() => setDeveloper(null)}
          onSaved={() => list.reload()}
        />
      )}
    </section>
  );
}
