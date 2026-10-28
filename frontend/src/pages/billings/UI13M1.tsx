import { useCallback, useEffect, useState, type ReactNode } from "react";
import {
  Button,
  Modal,
  ConfirmDialog,
  Skeleton,
  Badge,
} from "../../shared/Controls";
import { TextInput, MoneyInput, SelectInput } from "../../shared/Inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { failure } from "../admin/client";
import { listProjectOptions } from "../projects/client";
import { listProjectBillStages } from "../billstages/client";
import { getMonthClose, monthStatus } from "../shared/api";
import { seoulMonth } from "../shared/PeriodBar";
import {
  createBilling,
  updateBilling,
  previewBilling,
  type BillingRow,
  type BillingDetail,
  type BillingUpdate,
  type BillingPreview,
} from "./client";
import tables from "../developers/Developers.module.css";
import styles from "./Billing.module.css";

export function UI13M1({
  initial,
  detail,
  onCancel,
  onSaved,
  onDirtyChange,
  attendance,
}: {
  initial?: BillingRow;
  detail?: BillingDetail;
  onCancel: () => void;
  onSaved: (saved: BillingDetail) => Promise<unknown> | void;
  onDirtyChange?: (dirty: boolean) => void;
  attendance?: (projectId: number, month: string) => ReactNode;
}) {
  const existing = detail?.billing;
  const [projectId, setProjectId] = useState(
    existing?.prj_id ?? initial?.prj_id ?? 0,
  );
  const [stageId, setStageId] = useState(
    existing?.bill_stg_id ?? initial?.bill_stg_id ?? 0,
  );
  const [month, setMonth] = useState(
    existing?.atrb_ym ?? initial?.atrb_ym ?? seoulMonth(),
  );
  const [additional, setAdditional] = useState(false),
    [order, setOrder] = useState(2);
  const [draft, setDraft] = useState<BillingUpdate>(() =>
    existing
      ? {
          bill_dt: existing.bill_dt,
          sply_amt: existing.sply_amt,
          vat_amt: existing.vat_amt,
          txinv_no: existing.txinv_no,
          txinv_dt: existing.txinv_dt,
          rcpt_plan_dt: existing.rcpt_plan_dt,
        }
      : {
          bill_dt: initial?.bill_dt,
          ...(initial
            ? { sply_amt: initial.sply_amt, vat_amt: initial.vat_amt }
            : {}),
        },
  );
  const [preview, setPreview] = useState<BillingPreview | null>(null);
  const [previewError, setPreviewError] = useState<ReturnType<
    typeof failure
  > | null>(null);
  const [loadingPreview, setLoadingPreview] = useState(false),
    [dirty, setDirty] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const projects = useAdminRead(listProjectOptions);
  const project = projects.data?.find((p) => p.id === projectId);
  const type =
    existing?.contract_type ?? project?.type ?? initial?.contract_type;
  const tk = type === "TK";
  const stages = useAdminRead(
    useCallback(
      () =>
        projectId && tk
          ? listProjectBillStages(projectId)
          : Promise.resolve(null),
      [projectId, tk],
    ),
  );
  const stage = stages.data?.items.find((s) => s.bill_stg_id === stageId);
  const effectiveMonth = tk
    ? draft.bill_dt?.slice(0, 7) || stage?.bill_plan_dt?.slice(0, 7) || month
    : month;
  const closes = useAdminRead(
    useCallback(
      () =>
        getMonthClose(
          `${effectiveMonth.slice(0, 4)}-01`,
          `${effectiveMonth.slice(0, 4)}-12`,
        ),
      [effectiveMonth],
    ),
  );
  const closed = monthStatus(closes.data ?? [], effectiveMonth) === "CLOSED";
  const write = useAdminWrite(async () => {});
  const lines = preview?.details ?? detail?.details ?? [];
  const error = write.error ?? previewError;
  const fieldError = (key: string) =>
    error?.field === key ? error.message : null;
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  const change = <K extends keyof BillingUpdate>(
    key: K,
    value: BillingUpdate[K],
  ) => {
    setDirty(true);
    write.clear();
    setDraft((d) => ({ ...d, [key]: value }));
  };
  const cancel = () => {
    if (!write.pending) dirty ? setLeaving(true) : onCancel();
  };
  const previewSource = async () => {
    setLoadingPreview(true);
    setPreviewError(null);
    try {
      const next = await previewBilling(
        projectId,
        tk ? stageId : undefined,
        tk ? undefined : month,
      );
      setPreview(next);
      setDraft((d) => ({
        ...d,
        sply_amt: d.sply_amt ?? next.sply_amt,
        vat_amt: d.vat_amt ?? next.suggested_vat_amt,
        rcpt_plan_dt: d.rcpt_plan_dt ?? next.rcpt_plan_dt,
      }));
    } catch (e) {
      setPreviewError(failure(e));
    } finally {
      setLoadingPreview(false);
    }
  };
  const save = () => {
    void write.save(
      async () => {
        const saved = existing?.bill_id
          ? await updateBilling(existing.bill_id, draft)
          : await createBilling({
              ...draft,
              project_id: projectId,
              ...(tk
                ? { stage_id: stageId }
                : {
                    month,
                    additional,
                    ...(additional ? { bill_odr_no: order } : {}),
                  }),
            });
        await onSaved(saved);
      },
      () => {
        setDirty(false);
        onCancel();
      },
    );
  };
  return (
    <Modal title={existing ? "청구 수정" : "청구 등록"} onCancel={cancel}>
      <form
        data-testid="billing-editor"
        className={styles.form}
        onSubmit={(e) => {
          e.preventDefault();
          save();
        }}
      >
        <ErrorBanner error={error} />
        <ErrorBanner error={projects.error ?? stages.error ?? closes.error} />
        {closed && (
          <p role="status">
            <Badge tone="locked">마감된 달은 변경할 수 없습니다.</Badge>
          </p>
        )}
        {projects.loading && <Skeleton />}
        <div className={styles.fields}>
          <SelectInput
            label="프로젝트"
            name="project_id"
            required
            disabled={!!existing}
            value={String(projectId || "")}
            error={fieldError("project_id")}
            options={[
              { value: "", label: "선택" },
              ...(projects.data ?? [])
                .filter((p) => p.type !== "IN")
                .map((p) => ({
                  value: String(p.id),
                  label: `${p.no} · ${p.name}`,
                })),
            ]}
            onValueChange={(v) => {
              setProjectId(Number(v));
              setStageId(0);
              setPreview(null);
              setDraft({});
              setDirty(true);
            }}
          />
          {tk ? (
            <SelectInput
              label="TK 미청구 단계"
              name="stage_id"
              required
              disabled={!!existing}
              value={String(stageId || "")}
              error={fieldError("stage_id")}
              options={[
                { value: "", label: "선택" },
                ...(stages.data?.items ?? []).map((s) => ({
                  value: String(s.bill_stg_id),
                  label: `${s.bill_stg_nm} · ${s.bill_plan_dt}`,
                })),
              ]}
              onValueChange={(v) => {
                const selected = stages.data?.items.find(
                  (s) => s.bill_stg_id === Number(v),
                );
                setStageId(Number(v));
                setPreview(null);
                setDirty(true);
                setDraft((d) => ({
                  ...d,
                  bill_dt: selected?.bill_plan_dt ?? null,
                }));
              }}
            />
          ) : (
            <TextInput
              label="귀속월"
              name="month"
              type="month"
              required
              readOnly={!!existing}
              value={month}
              error={fieldError("month")}
              onValueChange={(v) => {
                setMonth(v);
                setPreview(null);
                setDirty(true);
              }}
            />
          )}
          {!existing && !tk && (
            <>
              <label>
                <input
                  data-testid="billing-additional"
                  type="checkbox"
                  checked={additional}
                  onChange={(e) => {
                    setAdditional(e.target.checked);
                    setDirty(true);
                  }}
                />
                추가 청구 명시
              </label>
              {additional && (
                <label>
                  차수
                  <input
                    data-testid="billing-order"
                    type="number"
                    min="2"
                    value={order}
                    onChange={(e) => {
                      setOrder(Number(e.target.value));
                      setDirty(true);
                    }}
                  />
                </label>
              )}
            </>
          )}
          <MoneyInput
            label="공급가액"
            name="sply_amt"
            value={draft.sply_amt ?? null}
            error={fieldError("sply_amt")}
            onValueChange={(v) => change("sply_amt", v ?? undefined)}
          />
          <MoneyInput
            label="부가세"
            name="vat_amt"
            value={draft.vat_amt ?? null}
            error={fieldError("vat_amt")}
            onValueChange={(v) => change("vat_amt", v ?? undefined)}
          />
          <MoneyInput
            label="합계"
            name="tot_amt"
            readOnly
            value={
              draft.sply_amt == null || draft.vat_amt == null
                ? null
                : draft.sply_amt + draft.vat_amt
            }
            onValueChange={() => {}}
          />
          <TextInput
            label="청구일"
            name="bill_dt"
            type="date"
            value={draft.bill_dt ?? ""}
            error={fieldError("bill_dt")}
            onValueChange={(v) => change("bill_dt", v || null)}
          />
          <TextInput
            label="세금계산서 번호"
            name="txinv_no"
            maxLength={50}
            value={draft.txinv_no ?? ""}
            error={fieldError("txinv_no")}
            onValueChange={(v) => change("txinv_no", v || null)}
          />
          <TextInput
            label="작성일"
            name="txinv_dt"
            type="date"
            value={draft.txinv_dt ?? ""}
            error={fieldError("txinv_dt")}
            onValueChange={(v) => change("txinv_dt", v || null)}
          />
          <TextInput
            label="입금 예정일"
            name="rcpt_plan_dt"
            type="date"
            value={draft.rcpt_plan_dt ?? ""}
            error={fieldError("rcpt_plan_dt")}
            onValueChange={(v) => change("rcpt_plan_dt", v || null)}
          />
          {existing && (
            <label>
              <input
                data-testid="billing-recalculate"
                type="checkbox"
                checked={draft.recalculate ?? false}
                onChange={(e) => change("recalculate", e.target.checked)}
              />
              저장 시 원천 상세 다시 계산
            </label>
          )}
        </div>
        <p>
          합계는 서버에서 계산합니다. 세금계산서는 발행하지 않고 번호와 날짜만
          기록합니다.
        </p>
        <Button
          data-testid="billing-preview"
          type="button"
          pending={loadingPreview}
          disabled={!projectId || (tk ? !stageId : !month)}
          onClick={() => void previewSource()}
        >
          미리보기
        </Button>
        {preview?.warnings.map((w, i) => (
          <p key={i}>
            <Badge tone="warning">{w}</Badge>
          </p>
        ))}
        {!!preview?.missing_price_assignments.length && (
          <p role="alert">단가 없는 인력이 있어 자동 생성할 수 없습니다.</p>
        )}
        {!!lines.length && (
          <div className={tables.tableScroll}>
            <table className={tables.table}>
              <thead>
                <tr>
                  <th>인력 / TK 청구 단계</th>
                  <th>MM</th>
                  <th>단가</th>
                  <th>초과근무금액</th>
                  <th>상세 금액</th>
                </tr>
              </thead>
              <tbody>
                {lines.map((line, index) => (
                  <tr
                    key={line.asgn_id ?? `stage-${index}`}
                    data-testid={`billing-source-${line.asgn_id ?? index}`}
                  >
                    <td>{line.display_name}</td>
                    <td>{line.mm_qty ?? "—"}</td>
                    <td className={tables.amount}>
                      {line.untprc_amt?.toLocaleString("ko-KR") ?? "—"}
                    </td>
                    <td>
                      {line.asgn_id !== null ? (
                        <MoneyInput
                          label={`${line.display_name} 초과근무금액`}
                          name={`overtime-${line.asgn_id}`}
                          value={
                            draft.details?.find(
                              (d) => d.asgn_id === line.asgn_id,
                            )?.ovtm_amt ?? line.ovtm_amt
                          }
                          onValueChange={(v) =>
                            change("details", [
                              ...(draft.details ?? []).filter(
                                (d) => d.asgn_id !== line.asgn_id,
                              ),
                              { asgn_id: line.asgn_id!, ovtm_amt: v ?? 0 },
                            ])
                          }
                        />
                      ) : (
                        "—"
                      )}
                    </td>
                    <td className={tables.amount}>
                      {line.bill_amt.toLocaleString("ko-KR")}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {lines.length > 0 &&
          draft.sply_amt != null &&
          lines.reduce((sum, l) => sum + l.bill_amt, 0) !== draft.sply_amt && (
            <p>
              <Badge tone="warning">
                상세 합계와 공급가액이 다릅니다. 확정할 수 있습니다.
              </Badge>
            </p>
          )}
        {type === "DP" && attendance?.(projectId, month)}
        <div className={styles.actions}>
          <Button
            data-testid="billing-editor-cancel"
            type="button"
            disabled={write.pending}
            onClick={cancel}
          >
            취소
          </Button>
          <Button
            data-testid="billing-editor-save"
            type="submit"
            variant="primary"
            pending={write.pending}
            disabled={
              closed ||
              !!closes.error ||
              closes.loading ||
              !!preview?.missing_price_assignments.length ||
              !projectId ||
              (tk && !stageId) ||
              write.uncertain
            }
          >
            저장
          </Button>
        </div>
      </form>
      {leaving && (
        <ConfirmDialog
          title="입력을 버릴까요?"
          onCancel={() => setLeaving(false)}
          onConfirm={onCancel}
        >
          저장하지 않은 입력이 있습니다.
        </ConfirmDialog>
      )}
    </Modal>
  );
}
