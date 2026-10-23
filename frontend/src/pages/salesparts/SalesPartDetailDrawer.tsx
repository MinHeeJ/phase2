import { useEffect, useRef, useState, type ReactNode } from "react";
import { Badge, Button, ConfirmDialog, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import type { ApiFailure } from "../../shared/api";
import { getSalesPart, type SalesPartDetail } from "./client";
import { failure } from "./SalesPartCreateDialog";
import styles from "./SalesParts.module.css";

export function SalesPartDetailDrawer({
  id,
  onClose,
  renderActions,
  onDirtyChange,
}: {
  id: string;
  onClose: () => void;
  renderActions?: (
    part: SalesPartDetail,
    onDetailChange: (part: SalesPartDetail) => void,
    onDirtyChange: (dirty: boolean) => void,
  ) => ReactNode;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [part, setPart] = useState<SalesPartDetail | null>(null),
    [error, setError] = useState<ApiFailure | null>(null);
  const [generation, setGeneration] = useState(0),
    [loading, setLoading] = useState(true);
  const panel = useRef<HTMLElement>(null);
  const [dirty, setDirty] = useState(false),
    [confirmClose, setConfirmClose] = useState(false);
  const dirtyRef = useRef(false);
  dirtyRef.current = dirty;
  const requestClose = () => {
    if (dirtyRef.current) setConfirmClose(true);
    else onClose();
  };
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    panel.current?.focus();
    const close = (event: KeyboardEvent) => {
      const dialog =
        event.target instanceof Element
          ? event.target.closest('[role="dialog"]')
          : null;
      if (event.key === "Escape" && (!dialog || dialog === panel.current))
        requestClose();
    };
    document.addEventListener("keydown", close);
    return () => {
      document.removeEventListener("keydown", close);
      previous?.focus();
    };
  }, [onClose]);
  useEffect(() => {
    let alive = true;
    setLoading(true);
    setPart(null);
    setError(null);
    void getSalesPart(id)
      .then((data) => {
        if (alive) setPart(data);
      })
      .catch((e: unknown) => {
        if (alive) setError(failure(e));
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [id, generation]);
  return (
    <div
      className={styles.backdrop}
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) requestClose();
      }}
    >
      <aside
        role="dialog"
        aria-modal="true"
        aria-label="영업 파트 상세"
        tabIndex={-1}
        ref={panel}
        className={styles.drawer}
        data-testid="sales-part-detail-drawer"
      >
        <header className={styles.toolbar}>
          <h2>
            {part?.bsn_part_nm ?? "영업 파트 상세"}{" "}
            {part?.hq_yn === "Y" && <Badge>본사</Badge>}
          </h2>
          <Button data-testid="sales-part-detail-close" onClick={requestClose}>
            닫기
          </Button>
        </header>
        {loading && <Skeleton />}
        <ErrorBanner
          error={error}
          onRetry={() => setGeneration((n) => n + 1)}
        />
        {part && (
          <>
            <p>{part.referenceMonth} 기준</p>
            {part.use_yn === "N" && (
              <p className={styles.notice}>사용을 중지한 파트입니다.</p>
            )}
            {part.hq_yn !== "Y" && (
              <div className={styles.writeActions}>
                {renderActions?.(part, setPart, setDirty)}
              </div>
            )}
            <dl className={styles.facts}>
              <dt>파트장</dt>
              <dd>{part.currentStandard?.ldr_nm ?? "–"}</dd>
              <dt>이익 배분율</dt>
              <dd>
                {part.currentStandard
                  ? `${part.currentStandard.prft_dstrb_rt}%`
                  : "–"}
              </dd>
              <dt>영업비(자사 인력 1인·월)</dt>
              <dd>
                {part.currentStandard
                  ? `${part.currentStandard.bsn_fee_amt.toLocaleString("ko-KR")}원`
                  : "–"}
              </dd>
              <dt>소속 인력</dt>
              <dd>{part.peopleCount}명</dd>
              <dt>진행 프로젝트</dt>
              <dd>{part.projectCount}건</dd>
              <dt>사용 여부</dt>
              <dd>{part.use_yn === "Y" ? "사용" : "중지"}</dd>
              <dt>적용 기간</dt>
              <dd>
                {part.currentStandard
                  ? `${part.currentStandard.aply_bgn_ym} ~ ${part.currentStandard.aply_end_ym ?? "현재"}`
                  : "현재 적용 기준 없음"}
              </dd>
            </dl>
            <h3>기준 이력</h3>
            <div className={styles.tableScroll}>
              <table>
                <thead>
                  <tr>
                    <th>적용 기간</th>
                    <th>파트장</th>
                    <th>배분율</th>
                    <th>영업비</th>
                    <th>상태</th>
                  </tr>
                </thead>
                <tbody>
                  {part.standards.map((s) => (
                    <tr
                      key={s.part_std_hist_id}
                      data-testid={`sales-part-standard-${s.part_std_hist_id}`}
                    >
                      <td>
                        {s.aply_bgn_ym} ~ {s.aply_end_ym ?? "현재"}
                      </td>
                      <td>{s.ldr_nm ?? "–"}</td>
                      <td>{s.prft_dstrb_rt}%</td>
                      <td>{s.bsn_fee_amt.toLocaleString("ko-KR")}원</td>
                      <td>
                        <Badge
                          tone={s.status === "예정" ? "warning" : "neutral"}
                        >
                          {s.status}
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {!part.standards.length && <p>기준 이력이 없습니다.</p>}
            <p className={styles.notice}>
              {part.lastClosedMonth
                ? `마감된 달(${part.lastClosedMonth})까지의 정산에는 그때 기준이 그대로 쓰입니다. 새 기준은 ${part.firstAllowedMonth}부터 넣을 수 있습니다.`
                : `마감 이력이 없습니다. 새 기준은 ${part.firstAllowedMonth}부터 넣을 수 있습니다.`}
            </p>
          </>
        )}
      </aside>
      {confirmClose && (
        <ConfirmDialog
          title="저장하지 않은 내용이 있습니다."
          confirmLabel="닫기"
          cancelLabel="계속 작성"
          onCancel={() => setConfirmClose(false)}
          onConfirm={onClose}
        >
          닫으면 입력한 내용이 사라집니다.
        </ConfirmDialog>
      )}
    </div>
  );
}
