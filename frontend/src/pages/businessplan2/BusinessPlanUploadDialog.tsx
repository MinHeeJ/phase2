import { useRef, useState } from "react";
import { Badge, Button, Modal } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { ApiFailure } from "../../shared/api";
import { downloadUploadFile, uploadFailure } from "../uploadsops/client";
import table from "../developers/Developers.module.css";
import styles from "../uploadsops/Uploads.module.css";
import {
  validateBusinessPlanUpload,
  applyBusinessPlanUpload,
  type BusinessPlanUploadResult,
} from "./client";

export interface BusinessPlanUploadDialogProps {
  readOnly?: boolean;
  onApplied: (result: BusinessPlanUploadResult) => void | Promise<unknown>;
  onClose: () => void;
}
export function BusinessPlanUploadDialog({
  readOnly = false,
  onApplied,
  onClose,
}: BusinessPlanUploadDialogProps) {
  const [file, setFile] = useState<File | null>(null);
  const [review, setReview] = useState<BusinessPlanUploadResult | null>(null);
  const [error, setError] = useState<ApiFailure | null>(null);
  const [pending, setPending] = useState(false),
    [consent, setConsent] = useState(false);
  const [notice, setNotice] = useState(""),
    [tab, setTab] = useState("NORMAL"),
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
  const show = (result: BusinessPlanUploadResult) => {
    setReview(result);
    setConsent(false);
    setPage(0);
    setTab(result.error_count ? "ERROR" : "NORMAL");
  };
  const inspect = () =>
    void run(async () => {
      if (file) {
        show(await validateBusinessPlanUpload(file));
        setNotice("");
      }
    });
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
  const download = (path: string, name: string) =>
    void run(() => downloadUploadFile(path, name));
  return (
    <Modal
      title={review ? "엑셀 업로드 결과 · 사업계획" : "엑셀 업로드 · 사업계획"}
      onCancel={() => {
        if (!pending) onClose();
      }}
    >
      <section
        data-testid="business-plan-upload"
        data-screen-id="SCR-UI-18-UPLOAD"
      >
        <p className={styles.info}>
          B.10 · 연도 · 월 · 파트 · 목표매출 · 목표영업이익 · 목표가동률
        </p>
        <p className={styles.info}>
          연도와 월은 파일에서 읽습니다. 마감 월의 목표가 다르거나 오류가
          하나라도 있으면 전체 미반영합니다.
        </p>
        {readOnly && (
          <p className={styles.info}>과거 연도는 조회·다운로드만 가능합니다.</p>
        )}
        <Can perm="UI-18.download">
          <Button
            data-testid="business-plan-template"
            disabled={pending}
            onClick={() =>
              download(
                "/api/uploads/templates/B.10",
                "사업계획_업로드양식.xlsx",
              )
            }
          >
            양식 내려받기
          </Button>
        </Can>
        {!readOnly && (
          <Can perm="UI-18.upload">
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
                  data-testid="business-plan-file"
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
                data-testid="business-plan-validate"
                variant="primary"
                disabled={!file || pending}
                onClick={inspect}
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
            data-testid="business-plan-success"
            className={styles.success}
          >
            {notice}
          </p>
        )}
        {review && (
          <>
            <p className={styles.warning}>{review.overwrite_notice}</p>
            <div className={styles.summary}>
              <div className={styles.success}>전체 {review.row_count}행</div>
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
                  data-testid={`business-plan-tab-${key.toLowerCase()}`}
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
              data-testid="business-plan-review"
            >
              <table className={table.table}>
                <thead>
                  <tr>
                    {[
                      "행",
                      "상태",
                      "연월",
                      "파트 ID",
                      "목표매출",
                      "목표영업이익",
                      "목표가동률",
                    ].map((h) => (
                      <th key={h}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rows.slice(page * 50, (page + 1) * 50).map((row) => (
                    <tr
                      data-testid={`business-plan-row-${row.row}`}
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
                      <td>{row.plan?.month ?? "–"}</td>
                      <td>{row.plan?.partId ?? "–"}</td>
                      <td className={table.amount}>
                        {row.plan?.revenue.toLocaleString("ko-KR") ?? "–"}
                      </td>
                      <td className={table.amount}>
                        {row.plan?.profit.toLocaleString("ko-KR") ?? "–"}
                      </td>
                      <td>{row.plan ? `${row.plan.utilization}%` : "–"}</td>
                    </tr>
                  ))}
                  {rows.length === 0 && (
                    <tr>
                      <td colSpan={7} className={styles.empty}>
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
                          data-testid={`business-plan-issue-${i}`}
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
            <div className={styles.tools}>
              <Button
                data-testid="business-plan-prev"
                disabled={page === 0 || pending}
                onClick={() => setPage((p) => p - 1)}
              >
                이전
              </Button>
              <span>{page + 1}쪽</span>
              <Button
                data-testid="business-plan-next"
                disabled={
                  pending ||
                  (page + 1) * 50 >= Math.max(rows.length, issues.length)
                }
                onClick={() => setPage((p) => p + 1)}
              >
                다음
              </Button>
            </div>
            <Can perm="UI-18.download">
              <Button
                data-testid="business-plan-errors"
                disabled={pending}
                onClick={() =>
                  download(
                    `/api/uploads/${encodeURIComponent(review.upload_id)}/errors`,
                    "사업계획_오류.xlsx",
                  )
                }
              >
                오류 파일
              </Button>
            </Can>
            {!readOnly && (
              <Can perm="UI-18.upload">
                <div className={styles.footer}>
                  <Button
                    data-testid="business-plan-preview"
                    disabled={pending || !review.can_apply || !!notice}
                    onClick={() =>
                      void run(async () =>
                        show(
                          await applyBusinessPlanUpload(
                            review.upload_id,
                            false,
                          ),
                        ),
                      )
                    }
                  >
                    미리보기
                  </Button>
                  <label>
                    <input
                      data-testid="business-plan-consent"
                      type="checkbox"
                      checked={consent}
                      disabled={pending || !review.can_apply || !!notice}
                      onChange={(e) => setConsent(e.target.checked)}
                    />
                    미마감 월 전체 교체에 동의합니다
                  </label>
                  <Button
                    data-testid="business-plan-apply"
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
                        const result = await applyBusinessPlanUpload(
                          review.upload_id,
                          true,
                        );
                        setReview({ ...result, can_apply: false });
                        setConsent(false);
                        setNotice(`${result.applied_count}건을 반영했습니다.`);
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
          data-testid="business-plan-close"
          disabled={pending}
          onClick={onClose}
        >
          닫기
        </Button>
      </section>
    </Modal>
  );
}
