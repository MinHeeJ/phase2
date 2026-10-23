import { ApiFailure } from "./api";
import styles from "./Controls.module.css";

export function ErrorBanner({
  error,
  onRetry,
  pending = false,
}: {
  error: ApiFailure | null;
  onRetry: () => void;
  pending?: boolean;
}) {
  if (!error) return null;
  return (
    <section role="alert" className={styles.errorBanner}>
      <div>{error.message}</div>
      <div>
        {error.requestId
          ? `문의 번호 ${error.requestId}`
          : error.status === null
            ? "문의 번호 없음 (서버 응답 없음)"
            : "문의 번호 없음"}
      </div>
      {error.uncertainWrite && (
        <p>저장 결과를 다시 조회한 뒤 확인하고 직접 저장해 주세요.</p>
      )}
      <button type="button" onClick={onRetry} disabled={pending}>
        다시 시도
      </button>
    </section>
  );
}
