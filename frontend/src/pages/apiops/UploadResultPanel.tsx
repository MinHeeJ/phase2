import { useEffect, useState, type ReactNode } from "react";
import { ApiFailure } from "../../shared/api";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { getUploadResult, type UploadResult } from "./client";
import styles from "../uploadsops/Uploads.module.css";

type Review = {
  id: number;
  result: UploadResult | null;
  error: ApiFailure | null;
};

// Embedded in the common upload result dialog; row/issue tabs and actions belong to its caller.
export function UploadResultPanel({
  uploadId,
  children,
}: {
  uploadId: number;
  children?: (result: UploadResult) => ReactNode;
}) {
  const [review, setReview] = useState<Review | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setReview(null);
    getUploadResult(uploadId, controller.signal).then(
      (result) => {
        if (active) setReview({ id: uploadId, result, error: null });
      },
      (error: unknown) => {
        if (active)
          setReview({
            id: uploadId,
            result: null,
            error:
              error instanceof ApiFailure
                ? error
                : new ApiFailure("검사 결과를 읽을 수 없습니다.", null, null),
          });
      },
    );
    return () => {
      active = false;
      controller.abort();
    };
  }, [uploadId, attempt]);

  const current = review?.id === uploadId ? review : null;
  const result = current?.result;
  return (
    <section data-testid="upload-result-panel" aria-busy={!current}>
      {!current && <p role="status">검사 결과 조회 중</p>}
      <ErrorBanner
        error={current?.error ?? null}
        onRetry={() => setAttempt((value) => value + 1)}
      />
      {result && (
        <>
          <h2>
            {result.form_type === "B.1"
              ? `엑셀 업로드 결과 · 급여 ${result.month}`
              : "엑셀 업로드 결과 · 개발자"}
          </h2>
          {result.form_type === "B.1" &&
            result.existing_salary_count > 0 &&
            result.overwrite_notice && (
              <p
                role="note"
                className={styles.warning}
                data-testid="upload-overwrite-notice"
              >
                {result.overwrite_notice}
              </p>
            )}
          <p>총 {result.row_count}행</p>
          <div className={styles.summary}>
            <div className={styles.success}>
              성공 <strong>{result.row_count - result.error_count}</strong>
            </div>
            <div className={styles.warning}>
              경고 <strong>{result.warning_count}</strong>
            </div>
            <div className={styles.danger}>
              오류 <strong>{result.error_count}</strong>
            </div>
          </div>
          <p className={result.error_count ? styles.dangerBanner : styles.info}>
            {result.error_count
              ? "오류가 하나라도 있으면 파일 전체를 반영하지 않습니다."
              : "검사가 끝났습니다. 경고는 반영을 막지 않습니다."}
          </p>
          {!result.can_apply && result.error_count === 0 && (
            <p className={styles.info}>
              현재 검사 결과는 반영할 수 없습니다. 다시 검사하거나 파일을 다시
              올려 주세요.
            </p>
          )}
          {result.row_count === 0 && (
            <p className={styles.empty}>검사한 데이터가 없습니다.</p>
          )}
          <p className={styles.info}>검사는 업무 데이터 반영이 아닙니다.</p>
          {children?.(result)}
        </>
      )}
    </section>
  );
}
