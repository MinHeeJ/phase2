import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ComponentProps,
} from "react";
import { useLocation } from "react-router-dom";
import UI14Screen, {
  type ClosingWorkspaceContext,
} from "../closing/UI14Screen";
import {
  Badge,
  Button,
  Modal,
  Skeleton,
  SuccessToast,
} from "../../shared/Controls";
import { Can } from "../../shared/Can";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { formatDateTime } from "../../lib/format";
import { ApiFailure } from "../../shared/api";
import { useAdminRead } from "../admin/useAdminResource";
import { useSharedMobile } from "../shared/PlaceholderScreen";
import { seoulMonth } from "../shared/PeriodBar";
import { AdjustmentPanel } from "../adjustments/AdjustmentPanel";
import tables from "../developers/Developers.module.css";
import styles from "./ClosingOperations.module.css";
import {
  confirmClosing,
  listClosingHistory,
  previewClosing,
  reopenClosing,
  runClosingCheck,
  type ClosingPreview,
} from "./client";

const asFailure = (error: unknown) =>
  error instanceof ApiFailure
    ? error
    : new ApiFailure("처리 중 오류가 발생했습니다.", null, null);
const money = (value: number) => `${value.toLocaleString("ko-KR")}원`;
export default function ClosingWorkspace(
  props: ComponentProps<typeof UI14Screen>,
) {
  const mobile = useSharedMobile();
  const [dialog, setDialog] = useState<{
    kind: "confirm" | "reopen";
    context: ClosingWorkspaceContext;
  } | null>(null);
  const [preview, setPreview] = useState<ClosingPreview | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [ackWarnings, setAckWarnings] = useState(false);
  const [reason, setReason] = useState("");
  const [validation, setValidation] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null);
  const [uncertain, setUncertain] = useState(false);
  const [message, setMessage] = useState("");
  const busy = useRef(false),
    serial = useRef(0);
  const contextRef = useRef<ClosingWorkspaceContext | null>(null);
  const dismiss = () => {
    if (!busy.current) {
      serial.current++;
      setDialog(null);
      setError(null);
      setValidation("");
    }
  };
  useEffect(() => {
    if (mobile && !busy.current) {
      serial.current++;
      setDialog(null);
    }
  }, [mobile]);
  useEffect(
    () => () => {
      serial.current++;
    },
    [],
  );
  const open = async (
    kind: "confirm" | "reopen",
    context: ClosingWorkspaceContext,
  ) => {
    const request = ++serial.current;
    contextRef.current = context;
    setDialog({ kind, context });
    setError(null);
    setValidation("");
    setReason("");
    setAckWarnings(false);
    setPreview(null);
    if (kind === "confirm") {
      setPreviewLoading(true);
      try {
        const result = await previewClosing(context.month);
        if (request === serial.current) setPreview(result);
      } catch (failure) {
        if (request === serial.current) setError(asFailure(failure));
      } finally {
        if (request === serial.current) setPreviewLoading(false);
      }
    }
  };
  const execute = async (
    context: ClosingWorkspaceContext,
    operation: () => Promise<unknown>,
    success: string,
  ) => {
    if (busy.current || uncertain || mobile) return;
    busy.current = true;
    contextRef.current = context;
    setPending(true);
    setError(null);
    let committed = false;
    try {
      await operation();
      committed = true;
      await context.refresh();
      serial.current++;
      setDialog(null);
      setMessage(success);
    } catch (failure) {
      const issue = asFailure(failure);
      setError(
        committed
          ? new ApiFailure(
              "저장 후 조회에 실패했습니다. 현재 상태를 확인하세요.",
              issue.status,
              issue.requestId,
              issue.field,
              issue.code,
              true,
            )
          : issue,
      );
      setUncertain(committed || issue.uncertainWrite);
      if (!committed && issue.code === "CLOSE_CHECK_FAILED")
        await context.refresh().catch(() => {});
    } finally {
      busy.current = false;
      setPending(false);
    }
  };
  const recover = async () => {
    if (busy.current || !contextRef.current) return;
    busy.current = true;
    setPending(true);
    try {
      await contextRef.current.refresh();
      setUncertain(false);
      setError(null);
      setDialog(null);
    } catch (failure) {
      setError(asFailure(failure));
    } finally {
      busy.current = false;
      setPending(false);
    }
  };
  const submit = () => {
    if (!dialog || mobile) return;
    const { context, kind } = dialog;
    if (kind === "reopen") {
      if (!reason.trim() || reason.trim().length > 500) {
        setValidation("해제 사유를 1~500자로 입력하세요.");
        return;
      }
      void execute(
        context,
        () => reopenClosing(context.month, reason.trim()),
        "마감을 해제했습니다.",
      );
    } else {
      const warnings = context.closing.checks.some((c) => c.status === "WARN");
      if (
        !preview ||
        context.closing.checks.length !== 9 ||
        context.closing.checks.some((c) => c.status === "FAIL") ||
        (warnings && !ackWarnings)
      )
        return;
      void execute(
        context,
        () => confirmClosing(context.month, ackWarnings),
        "월 마감을 확정했습니다.",
      );
    }
  };
  const actions = (context: ClosingWorkspaceContext) => {
    const checked = context.closing.checks.length === 9;
    const failure = context.closing.checks.some((c) => c.status === "FAIL");
    return (
      !mobile && (
        <div className={tables.actions}>
          {context.closing.status === "OPEN" ? (
            <>
              <Can perm="UI-14.update">
                <Button
                  data-testid="closing-recheck-button"
                  pending={pending}
                  disabled={uncertain || context.month > seoulMonth()}
                  onClick={() =>
                    void execute(
                      context,
                      () => runClosingCheck(context.month),
                      "점검 결과를 저장했습니다.",
                    )
                  }
                >
                  다시 점검
                </Button>
              </Can>
              <Can perm="UI-14.confirm">
                <Button
                  data-testid="closing-confirm-button"
                  variant="primary"
                  disabled={
                    pending ||
                    uncertain ||
                    failure ||
                    !checked ||
                    context.month > seoulMonth()
                  }
                  onClick={() => void open("confirm", context)}
                >
                  마감하기
                </Button>
              </Can>
            </>
          ) : (
            context.closing.canReopen && (
              <Can perm="UI-14.reopen">
                <Button
                  data-testid="closing-reopen-button"
                  className={styles.danger}
                  disabled={pending || uncertain}
                  onClick={() => void open("reopen", context)}
                >
                  마감 해제
                </Button>
              </Can>
            )
          )}
        </div>
      )
    );
  };
  const summary = dialog?.context.closing.checks;
  const hasWarnings = summary?.some((c) => c.status === "WARN");
  return (
    <>
      <ErrorBanner error={error} />
      {uncertain && (
        <Button
          data-testid="closing-recover-button"
          pending={pending}
          onClick={() => void recover()}
        >
          현재 상태 확인
        </Button>
      )}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      <UI14Screen
        {...props}
        renderActions={actions}
        renderRelated={(context) => (
          <ClosingRelated key={context.month} context={context} />
        )}
      />
      {dialog && !mobile && (
        <Modal
          title={
            dialog.kind === "confirm"
              ? `${dialog.context.month}를 마감할까요?`
              : "마감 해제"
          }
          onCancel={dismiss}
        >
          <div data-testid="closing-operation-dialog">
            <p>대상 월: {dialog.context.month}</p>
            {dialog.kind === "confirm" ? (
              <>
                <p>
                  9종 점검 · OK{" "}
                  {summary?.filter((c) => c.status === "OK").length} · WARN{" "}
                  {summary?.filter((c) => c.status === "WARN").length} · FAIL{" "}
                  {summary?.filter((c) => c.status === "FAIL").length}
                </p>
                <p>
                  원가 배분·손익 상세·법인세 월할·실제 현금 종료잔액을
                  스냅샷으로 저장합니다. 마감한 달의 원천은 수정할 수 없습니다.
                </p>
                {previewLoading && <Skeleton label="잠정 수치 불러오는 중" />}
                {preview && (
                  <dl className={styles.amounts}>
                    {(
                      [
                        ["영업이익", preview.profit],
                        ["매출원가", preview.cost],
                        ["법인세", preview.tax],
                        ["현금 종료잔액", preview.cash],
                      ] as const
                    ).map(([name, value]) => (
                      <div key={name}>
                        <dt>{name}</dt>
                        <dd>{money(value)}</dd>
                      </div>
                    ))}
                  </dl>
                )}
                {preview?.warnings?.map((warning) => (
                  <p key={warning} className={styles.warning}>
                    {warning}
                  </p>
                ))}
                {hasWarnings && (
                  <label>
                    <input
                      data-testid="closing-warning-ack-input"
                      type="checkbox"
                      checked={ackWarnings}
                      disabled={pending}
                      onChange={(e) => setAckWarnings(e.target.checked)}
                    />
                    경고 내용을 확인했으며 마감에 동의합니다.
                  </label>
                )}
              </>
            ) : (
              <>
                <p>
                  가장 최근에 마감한 달만 해제할 수 있습니다. 원천·이관 자료와
                  과거 마감 이력은 보존됩니다.
                </p>
                <label className={styles.reason}>
                  해제 사유
                  <textarea
                    data-testid="closing-reopen-reason-input"
                    value={reason}
                    maxLength={500}
                    disabled={pending}
                    aria-invalid={!!validation || error?.field === "reason"}
                    onChange={(e) => {
                      setReason(e.target.value);
                      setValidation("");
                    }}
                  />
                </label>
              </>
            )}
            {validation && <p role="alert">{validation}</p>}
            {error && <ErrorBanner error={error} />}
            <div className={tables.actions}>
              <Button
                data-testid="closing-dialog-cancel-button"
                disabled={pending}
                onClick={dismiss}
              >
                취소
              </Button>
              <Button
                data-testid="closing-dialog-submit-button"
                variant="primary"
                className={dialog.kind === "reopen" ? styles.danger : ""}
                pending={pending}
                disabled={
                  uncertain ||
                  (dialog.kind === "confirm" &&
                    (!preview ||
                      previewLoading ||
                      !!error ||
                      (!!hasWarnings && !ackWarnings)))
                }
                onClick={submit}
              >
                {dialog.kind === "confirm" ? "마감하기" : "마감 해제"}
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </>
  );
}

function ClosingRelated({ context }: { context: ClosingWorkspaceContext }) {
  const location = useLocation();
  const [tab, setTab] = useState(() =>
    (location.state as { closingTab?: string } | null)?.closingTab ===
    "adjustments"
      ? "adjustments"
      : "history",
  );
  useEffect(() => {
    if (
      location.pathname === "/UI-14" &&
      (location.state as { closingTab?: string } | null)?.closingTab ===
        "adjustments"
    )
      setTab("adjustments");
  }, [location.key, location.pathname, location.state]);
  return (
    <>
      <div
        role="tablist"
        aria-label="마감 관련 내역"
        className={tables.actions}
      >
        <Button
          data-testid="closing-history-tab"
          role="tab"
          aria-selected={tab === "history"}
          onClick={() => setTab("history")}
        >
          마감 이력
        </Button>
        <Button
          data-testid="closing-adjustments-tab"
          role="tab"
          aria-selected={tab === "adjustments"}
          onClick={() => setTab("adjustments")}
        >
          전월 조정
        </Button>
      </div>
      {tab === "history" ? (
        <ClosingHistory month={context.month} />
      ) : (
        <AdjustmentPanel month={context.month} onRefresh={context.refresh} />
      )}
    </>
  );
}
export function ClosingHistory({ month }: { month: string }) {
  const [page, setPage] = useState(0);
  const read = useCallback(
    () => listClosingHistory(Number(month.slice(0, 4)), page, 20),
    [month, page],
  );
  const query = useAdminRead(read);
  const rows = query.loading || query.error ? [] : (query.data?.items ?? []);
  return (
    <section
      role="tabpanel"
      aria-label="마감 이력"
      data-testid="closing-history-panel"
      className={tables.card}
    >
      <h2>마감 이력</h2>
      <ErrorBanner
        error={query.error}
        onRetry={() => void query.reload().catch(() => {})}
      />
      {query.loading && <Skeleton label="마감 이력 불러오는 중" />}
      {!query.loading && !query.error && (
        <>
          <p>연간 전체 {query.data?.total ?? 0}건</p>
          <div className={tables.tableScroll}>
            <table className={tables.table}>
              <thead>
                <tr>
                  {["연월", "작업", "처리자", "일시", "사유"].map((label) => (
                    <th key={label}>{label}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((row, index) => (
                  <tr
                    key={`${row.processedAt}-${row.action}-${index}`}
                    data-testid={`closing-history-row-${index}`}
                  >
                    <td>{row.month}</td>
                    <td>
                      <Badge
                        tone={row.action === "CLOSE" ? "locked" : "warning"}
                      >
                        {row.action === "CLOSE" ? "마감" : "해제"}
                      </Badge>
                    </td>
                    <td>{row.actor || "시스템 처리"}</td>
                    <td>{formatDateTime(row.processedAt)}</td>
                    <td>{row.reason || "–"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {rows.length === 0 && (
            <p className={tables.empty}>마감 이력이 없습니다.</p>
          )}
          <div className={tables.actions}>
            <Button
              data-testid="closing-history-prev-button"
              disabled={page === 0}
              onClick={() => setPage(page - 1)}
            >
              이전
            </Button>
            <span>{page + 1} 페이지</span>
            <Button
              data-testid="closing-history-next-button"
              disabled={(page + 1) * 20 >= (query.data?.total ?? 0)}
              onClick={() => setPage(page + 1)}
            >
              다음
            </Button>
          </div>
        </>
      )}
    </section>
  );
}
