import { useRef, useState } from "react";
import { Badge, Button, Modal } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { ApiFailure } from "../../shared/api";
import { useCodes } from "../shared/useCodes";
import { downloadUploadFile, uploadFailure } from "../uploadsops/client";
import table from "../developers/Developers.module.css";
import styles from "../uploadsops/Uploads.module.css";
import {
  validateFinanceEntriesUpload,
  applyFinanceEntriesUpload,
  type FinanceEntriesUploadResult,
} from "./client";

export interface FinanceEntriesUploadDialogProps {
  readOnly?: boolean;
  onApplied: (result: FinanceEntriesUploadResult) => void | Promise<unknown>;
  onClose: () => void;
}
export function FinanceEntriesUploadDialog({
  readOnly = false,
  onApplied,
  onClose,
}: FinanceEntriesUploadDialogProps) {
  const [file, setFile] = useState<File | null>(null);
  const [review, setReview] = useState<FinanceEntriesUploadResult | null>(null);
  const [error, setError] = useState<ApiFailure | null>(null);
  const [pending, setPending] = useState(false),
    [consent, setConsent] = useState(false);
  const [notice, setNotice] = useState(""),
    [tab, setTab] = useState("NORMAL"),
    [page, setPage] = useState(0);
  const busy = useRef(false);
  const codes = useCodes("FIN_CATEGORY");
  const category = (code: string) =>
    codes.codes.find((c) => c.code === code)?.name ?? code;
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
        [
          "CLOSED_MONTH",
          "INVALID_MONTH",
          "VALIDATION_ERROR",
          "NOT_FOUND",
        ].includes(failure.code ?? "")
      ) {
        setReview((r) => (r ? { ...r, can_apply: false } : r));
        setConsent(false);
      }
    } finally {
      busy.current = false;
      setPending(false);
    }
  };
  const show = (result: FinanceEntriesUploadResult) => {
    setReview(result);
    setConsent(false);
    setPage(0);
    setTab(result.error_count ? "ERROR" : "NORMAL");
  };
  const inspect = () =>
    void run(async () => {
      if (file) {
        show(await validateFinanceEntriesUpload(file));
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
      title={
        review ? "엑셀 업로드 결과 · 재무 항목" : "엑셀 업로드 · 재무 항목"
      }
      onCancel={() => {
        if (!pending) onClose();
      }}
    >
      <section
        data-testid="finance-entries-upload"
        data-screen-id="SCR-UI-10-UPLOAD"
      >
        <p className={styles.info}>
          B.2 · 연월 · 구분 · 항목 · 금액 필수, VAT · 입출금일 · 반복 · 비고
          선택
        </p>
        <p className={styles.info}>
          연월은 파일에서 읽습니다. 마감·미래월은 반영할 수 없으며 오류가
          하나라도 있으면 전체 미반영합니다.
        </p>
        {readOnly && (
          <p className={styles.info}>
            마감·과거 연도는 조회·다운로드만 가능합니다.
          </p>
        )}
        <Can perm="UI-10.download">
          <Button
            data-testid="finance-entries-template"
            disabled={pending}
            onClick={() =>
              download("/api/uploads/templates/B.2", "재무항목_업로드양식.xlsx")
            }
          >
            양식 내려받기
          </Button>
        </Can>
        {!readOnly && (
          <Can perm="UI-10.upload">
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
                  data-testid="finance-entries-file"
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
                data-testid="finance-entries-validate"
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
            data-testid="finance-entries-success"
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
              <div className={styles.warning}>
                경고 {review.warning_count}행
              </div>
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
                  data-testid={`finance-entries-tab-${key.toLowerCase()}`}
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
              data-testid="finance-entries-review"
            >
              <table className={table.table}>
                <thead>
                  <tr>
                    {[
                      "행",
                      "상태",
                      "연월",
                      "구분",
                      "세부 항목",
                      "금액",
                      "부가세",
                      "입출금일",
                      "매달 반복",
                      "비고",
                    ].map((h) => (
                      <th key={h}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rows.slice(page * 50, (page + 1) * 50).map((row) => (
                    <tr
                      data-testid={`finance-entries-row-${row.row}`}
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
                      <td>{row.entry?.entry_ym ?? "–"}</td>
                      <td>{row.entry ? category(row.entry.fin_se_cd) : "–"}</td>
                      <td>{row.entry?.item_nm ?? "–"}</td>
                      <td className={table.amount}>
                        {row.entry?.entry_amt.toLocaleString("ko-KR") ?? "–"}
                      </td>
                      <td className={table.amount}>
                        {row.entry?.vat_amt.toLocaleString("ko-KR") ?? "–"}
                      </td>
                      <td>{row.entry?.inout_dt ?? "–"}</td>
                      <td>{row.entry?.rpt_yn === "Y" ? "반복" : "–"}</td>
                      <td>{row.entry?.remark ?? "–"}</td>
                    </tr>
                  ))}
                  {rows.length === 0 && (
                    <tr>
                      <td colSpan={10} className={styles.empty}>
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
                      <th>입력값</th>
                      <th>이유</th>
                    </tr>
                  </thead>
                  <tbody>
                    {issues
                      .slice(page * 50, (page + 1) * 50)
                      .map((issue, i) => (
                        <tr
                          key={`${issue.row}-${i}`}
                          data-testid={`finance-entries-issue-${i}`}
                        >
                          <td>{issue.row}</td>
                          <td>{issue.field}</td>
                          <td>{issue.raw_value ?? "–"}</td>
                          <td>{issue.message}</td>
                        </tr>
                      ))}
                    {issues.length === 0 && (
                      <tr>
                        <td colSpan={4} className={styles.empty}>
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
                data-testid="finance-entries-prev"
                disabled={page === 0 || pending}
                onClick={() => setPage((p) => p - 1)}
              >
                이전
              </Button>
              <span>{page + 1}쪽</span>
              <Button
                data-testid="finance-entries-next"
                disabled={
                  pending ||
                  (page + 1) * 50 >= Math.max(rows.length, issues.length)
                }
                onClick={() => setPage((p) => p + 1)}
              >
                다음
              </Button>
            </div>
            <Can perm="UI-10.download">
              <Button
                data-testid="finance-entries-errors"
                disabled={pending}
                onClick={() =>
                  download(
                    `/api/uploads/${encodeURIComponent(review.upload_id)}/errors`,
                    "재무항목_오류.xlsx",
                  )
                }
              >
                오류 파일
              </Button>
            </Can>
            {!readOnly && (
              <Can perm="UI-10.upload">
                <div className={styles.footer}>
                  <Button
                    data-testid="finance-entries-preview"
                    disabled={pending || !review.can_apply || !!notice}
                    onClick={() =>
                      void run(async () =>
                        show(
                          await applyFinanceEntriesUpload(
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
                      data-testid="finance-entries-consent"
                      type="checkbox"
                      checked={consent}
                      disabled={pending || !review.can_apply || !!notice}
                      onChange={(e) => setConsent(e.target.checked)}
                    />
                    해당 연월 전체 교체에 동의합니다
                  </label>
                  <Button
                    data-testid="finance-entries-apply"
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
                        const result = await applyFinanceEntriesUpload(
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
          data-testid="finance-entries-close"
          disabled={pending}
          onClick={onClose}
        >
          닫기
        </Button>
      </section>
    </Modal>
  );
}
