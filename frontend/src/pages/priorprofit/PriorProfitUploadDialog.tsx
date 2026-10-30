import { useRef, useState } from "react";
import { Badge, Button, Modal } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { ApiFailure } from "../../shared/api";
import { downloadUploadFile, uploadFailure } from "../uploadsops/client";
import table from "../developers/Developers.module.css";
import styles from "../uploadsops/Uploads.module.css";
import {
  validatePriorProfitUpload,
  applyPriorProfitUpload,
  type PriorProfitUploadResult,
  type PriorProfitSnapshot,
} from "./client";

export interface PriorProfitUploadDialogProps {
  readOnly?: boolean;
  onApplied: (result: PriorProfitUploadResult) => void | Promise<unknown>;
  onClose: () => void;
}
const columns: {
  key: keyof Omit<PriorProfitSnapshot, "month">;
  label: string;
}[] = [
  { key: "revenue", label: "매출액" },
  { key: "allocatedCost", label: "배분 원가" },
  { key: "idleCost", label: "대기 원가" },
  { key: "directExpense", label: "직접 경비" },
  { key: "sgaInput", label: "판관비 입력 항목" },
  { key: "indirectLabor", label: "간접 인건비" },
  { key: "researchCost", label: "연구개발비" },
  { key: "adjustment", label: "전월 조정" },
  { key: "nonOperatingIncome", label: "영업외수익" },
  { key: "nonOperatingExpense", label: "영업외비용" },
  { key: "corporateTax", label: "법인세" },
  { key: "allocatedRevenue", label: "배분 기준 매출" },
];
export function PriorProfitUploadDialog({
  readOnly = false,
  onApplied,
  onClose,
}: PriorProfitUploadDialogProps) {
  const [file, setFile] = useState<File | null>(null),
    [review, setReview] = useState<PriorProfitUploadResult | null>(null);
  const [error, setError] = useState<ApiFailure | null>(null),
    [pending, setPending] = useState(false);
  const [consent, setConsent] = useState(false),
    [notice, setNotice] = useState("");
  const [tab, setTab] = useState("NORMAL"),
    [page, setPage] = useState(0);
  const busy = useRef(false);
  const run = async (action: () => Promise<void>) => {
    if (busy.current) return;
    busy.current = true;
    setPending(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      const failure = uploadFailure(e);
      setError(failure);
      if (
        ["CLOSED_MONTH", "VALIDATION_ERROR", "NOT_FOUND"].includes(
          failure.code ?? "",
        )
      ) {
        setReview((r) => (r ? { ...r, can_apply: false } : r));
        setConsent(false);
      }
    } finally {
      busy.current = false;
      setPending(false);
    }
  };
  const show = (result: PriorProfitUploadResult) => {
    setReview(result);
    setConsent(false);
    setPage(0);
    setTab(result.error_count ? "ERROR" : "NORMAL");
  };
  const choose = (chosen?: File) => {
    setFile(null);
    setReview(null);
    setError(null);
    setConsent(false);
    setNotice("");
    if (!chosen) return;
    if (
      !/\.xlsx$/i.test(chosen.name) ||
      chosen.size === 0 ||
      chosen.size > 10 * 1024 * 1024
    ) {
      setError(
        new ApiFailure(
          "xlsx · 10MB 이하의 파일을 선택하세요.",
          400,
          null,
          "file",
        ),
      );
      return;
    }
    setFile(chosen);
  };
  const rows = review?.rows.filter((r) => r.severity === tab) ?? [];
  const issues = review?.items.filter((i) => i.severity === tab) ?? [];
  const details = review?.details.filter((r) => r.severity === tab) ?? [];
  const download = (path: string, name: string) =>
    void run(() => downloadUploadFile(path, name));
  return (
    <Modal
      title={
        review ? "엑셀 업로드 결과 · 과거 손익" : "엑셀 업로드 · 과거 손익"
      }
      onCancel={() => {
        if (!pending) onClose();
      }}
    >
      <section
        data-testid="prior-profit-upload"
        data-screen-id="SCR-UI-08-UPLOAD"
      >
        <p className={styles.info}>
          B.11 · 2025년 1~12월 월별 손익 · 판관비 상세 이관
        </p>
        <p className={styles.info}>
          연월은 파일에서 읽습니다. 오류가 하나라도 있거나 2025년 CLOSE가 있으면
          연도 전체를 반영하지 않습니다. 인력·프로젝트·입금·지급은 생성하지
          않습니다.
        </p>
        {readOnly && (
          <p className={styles.warning}>
            CLOSE 손익은 읽기 전용입니다. 이관할 수 없습니다.
          </p>
        )}
        <Can perm="UI-08.download">
          <Button
            data-testid="prior-profit-template"
            disabled={pending}
            onClick={() =>
              download(
                "/api/uploads/templates/B.11",
                "과거손익_업로드양식.xlsx",
              )
            }
          >
            양식 내려받기
          </Button>
        </Can>
        {!readOnly && (
          <Can perm="UI-08.read">
            <div
              className={styles.dropzone}
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => {
                e.preventDefault();
                if (!pending) choose(e.dataTransfer.files[0]);
              }}
            >
              <p>파일을 끌어다 놓으세요</p>
              <label className={styles.fileLabel}>
                파일 선택
                <input
                  data-testid="prior-profit-file"
                  aria-label="파일 선택"
                  type="file"
                  accept=".xlsx"
                  disabled={pending}
                  onChange={(e) => choose(e.target.files?.[0])}
                />
              </label>
              <p>xlsx · 10MB 이하 · 데이터 10,000행 이하</p>
              {file && <p>{file.name}</p>}
              <Button
                data-testid="prior-profit-validate"
                variant="primary"
                disabled={!file || pending}
                onClick={() =>
                  void run(async () => {
                    if (file) {
                      show(await validatePriorProfitUpload(file));
                      setNotice("");
                    }
                  })
                }
              >
                {review ? "다시 검사" : "검사"}
              </Button>
            </div>
          </Can>
        )}
        <ErrorBanner error={error} />
        {pending && <p role="status">처리 중</p>}
        {notice && (
          <p
            role="status"
            data-testid="prior-profit-success"
            className={styles.success}
          >
            {notice}
          </p>
        )}
        {review && (
          <>
            <p className={styles.warning}>{review.overwrite_notice}</p>
            <div className={styles.summary}>
              <div className={styles.success}>전체 {review.row_count}개월</div>
              <div className={styles.danger}>오류 {review.error_count}행</div>
            </div>
            {review.error_count > 0 && (
              <p className={styles.dangerBanner}>
                오류가 하나라도 있으면 파일 전체를 반영하지 않습니다.
              </p>
            )}
            <div role="tablist" className={styles.tools}>
              {["NORMAL", "WARNING", "ERROR"].map((key, i) => (
                <Button
                  data-testid={`prior-profit-tab-${key.toLowerCase()}`}
                  role="tab"
                  aria-selected={tab === key}
                  key={key}
                  onClick={() => {
                    setTab(key);
                    setPage(0);
                  }}
                >
                  {["정상", "경고", "오류"][i]}
                </Button>
              ))}
            </div>
            <div
              className={table.tableScroll}
              role="tabpanel"
              data-testid="prior-profit-review"
            >
              <table className={table.table}>
                <thead>
                  <tr>
                    <th>행</th>
                    <th>상태</th>
                    <th>연월</th>
                    {columns.map((c) => (
                      <th key={c.key}>{c.label}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rows.slice(page * 50, (page + 1) * 50).map((row) => (
                    <tr
                      data-testid={`prior-profit-row-${row.row}`}
                      key={row.row}
                    >
                      <td>{row.row}</td>
                      <td>
                        <Badge
                          tone={
                            row.severity === "ERROR"
                              ? "danger"
                              : row.severity === "WARNING"
                                ? "warning"
                                : "success"
                          }
                        >
                          {row.severity === "ERROR"
                            ? "오류"
                            : row.severity === "WARNING"
                              ? "경고"
                              : "정상"}
                        </Badge>
                      </td>
                      <td>{row.profit?.month ?? "–"}</td>
                      {columns.map((c) => (
                        <td className={table.amount} key={c.key}>
                          {row.profit?.[c.key]?.toLocaleString("ko-KR") ?? "–"}
                        </td>
                      ))}
                    </tr>
                  ))}
                  {rows.length === 0 && (
                    <tr>
                      <td colSpan={15} className={styles.empty}>
                        해당 행이 없습니다.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            {tab !== "NORMAL" && (
              <div className={table.tableScroll}>
                <table className={table.table}>
                  <thead>
                    <tr>
                      <th>행</th>
                      <th>열</th>
                      <th>이유</th>
                    </tr>
                  </thead>
                  <tbody>
                    {issues
                      .slice(page * 50, (page + 1) * 50)
                      .map((issue, i) => (
                        <tr
                          key={`${issue.row}-${i}`}
                          data-testid={`prior-profit-issue-${i}`}
                        >
                          <td>{issue.row}</td>
                          <td>{issue.field}</td>
                          <td>{issue.message}</td>
                        </tr>
                      ))}
                    {issues.length === 0 && (
                      <tr>
                        <td colSpan={3} className={styles.empty}>
                          해당 이슈가 없습니다.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            )}
            <h3>판관비 상세</h3>
            <div className={table.tableScroll}>
              <table className={table.table}>
                <thead>
                  <tr>
                    <th>행</th>
                    <th>연월</th>
                    <th>항목 코드</th>
                    <th>금액</th>
                  </tr>
                </thead>
                <tbody>
                  {details.slice(page * 50, (page + 1) * 50).map((row) => (
                    <tr
                      key={row.row}
                      data-testid={`prior-profit-detail-${row.row}`}
                    >
                      <td>{row.row}</td>
                      <td>{row.detail?.month ?? "–"}</td>
                      <td>{row.detail?.code ?? "–"}</td>
                      <td className={table.amount}>
                        {row.detail?.amount.toLocaleString("ko-KR") ?? "–"}
                      </td>
                    </tr>
                  ))}
                  {details.length === 0 && (
                    <tr>
                      <td colSpan={4} className={styles.empty}>
                        해당 판관비 상세가 없습니다.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            <div className={styles.tools}>
              <Button
                data-testid="prior-profit-prev"
                disabled={page === 0 || pending}
                onClick={() => setPage((p) => p - 1)}
              >
                이전
              </Button>
              <span>{page + 1}쪽</span>
              <Button
                data-testid="prior-profit-next"
                disabled={
                  pending ||
                  (page + 1) * 50 >=
                    Math.max(rows.length, issues.length, details.length)
                }
                onClick={() => setPage((p) => p + 1)}
              >
                다음
              </Button>
            </div>
            <Can perm="UI-08.download">
              <Button
                data-testid="prior-profit-errors"
                disabled={pending}
                onClick={() =>
                  download(
                    `/api/uploads/${encodeURIComponent(review.upload_id)}/errors`,
                    "과거손익_오류.xlsx",
                  )
                }
              >
                오류 파일
              </Button>
            </Can>
            {!readOnly && (
              <Can perm="UI-08.read">
                <div className={styles.footer}>
                  <Button
                    data-testid="prior-profit-preview"
                    disabled={pending || !review.can_apply || !!notice}
                    onClick={() =>
                      void run(async () =>
                        show(
                          await applyPriorProfitUpload(review.upload_id, false),
                        ),
                      )
                    }
                  >
                    미리보기
                  </Button>
                  <label>
                    <input
                      data-testid="prior-profit-consent"
                      type="checkbox"
                      checked={consent}
                      disabled={pending || !review.can_apply || !!notice}
                      onChange={(e) => setConsent(e.target.checked)}
                    />
                    2025년 IMPORT 전체 교체에 동의합니다
                  </label>
                  <Button
                    data-testid="prior-profit-apply"
                    variant="primary"
                    pending={pending}
                    disabled={
                      !consent ||
                      !review.can_apply ||
                      review.error_count > 0 ||
                      !!notice
                    }
                    onClick={() =>
                      void run(async () => {
                        const result = await applyPriorProfitUpload(
                          review.upload_id,
                          true,
                        );
                        setReview({ ...result, can_apply: false });
                        setConsent(false);
                        setNotice(
                          `${result.applied_count}개월 손익을 반영했습니다.`,
                        );
                        await onApplied(result);
                      })
                    }
                  >
                    반영
                  </Button>
                </div>
              </Can>
            )}
          </>
        )}
        <Button
          data-testid="prior-profit-close"
          disabled={pending}
          onClick={onClose}
        >
          닫기
        </Button>
      </section>
    </Modal>
  );
}
