import { useCallback, useEffect, useRef, useState } from "react";
import { Button, ConfirmDialog, Skeleton } from "../../shared/Controls";
import {
  MoneyInput,
  TextInput,
  SelectInput,
  TextareaInput,
} from "../../shared/Inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { failure } from "../admin/client";
import { listProjectOptions, getProject } from "../projects/client";
import { listProjectBillStages } from "../billstages/client";
import { getMonthClose } from "../shared/api";
import {
  createBilling,
  updateBilling,
  previewBilling,
  type BillingDetail,
  type BillingCreate,
  type BillingUpdate,
  type BillingPreview,
} from "./client";
import { BillingLines } from "./UI13DetailPanel";
import styles from "./Billings.module.css";

export function UI13M1({
  existing,
  projectId,
  stageId,
  month: initialMonth,
  onCancel,
  onSaved,
  reload,
  onDirtyChange,
  onAttendance,
}: {
  existing?: BillingDetail;
  projectId?: number;
  stageId?: number;
  month?: string;
  onCancel: () => void;
  onSaved: (detail: BillingDetail) => void;
  reload: () => Promise<unknown>;
  onDirtyChange?: (dirty: boolean) => void;
  onAttendance?: (projectId: number, month: string) => void;
}) {
  const row = existing?.billing;
  const [project, setProject] = useState(row?.prj_id ?? projectId ?? 0);
  const [stage, setStage] = useState(row?.bill_stg_id ?? stageId);
  const [month, setMonth] = useState(row?.atrb_ym ?? initialMonth ?? "");
  const [additional, setAdditional] = useState(false);
  const [ordinal, setOrdinal] = useState(String(row?.bill_odr_no ?? 1));
  const [draft, setDraft] = useState<BillingUpdate>({
    bill_dt: row?.bill_dt ?? null,
    sply_amt: row?.sply_amt,
    vat_amt: row?.vat_amt,
    rcpt_plan_dt: row?.rcpt_plan_dt ?? null,
    txinv_no: row?.txinv_no ?? null,
    txinv_dt: row?.txinv_dt ?? null,
    ...(row ? { rmrk_desc: row.rmrk_desc ?? "" } : {}),
  });
  const [preview, setPreview] = useState<BillingPreview | null>(null);
  const [dirty, setDirty] = useState(false),
    [leaving, setLeaving] = useState(false);
  const [reading, setReading] = useState(false);
  const [local, setLocal] = useState<ReturnType<typeof failure> | null>(null);
  const manualSupply = useRef(!!row),
    manualVat = useRef(!!row);
  const projects = useAdminRead(listProjectOptions);
  const selected = useAdminRead(
    useCallback(
      () => (project ? getProject(project) : Promise.resolve(null)),
      [project],
    ),
  );
  const tk = (row?.contract_type ?? selected.data?.cntrct_ty_cd) === "TK";
  const stages = useAdminRead(
    useCallback(
      () =>
        project && tk ? listProjectBillStages(project) : Promise.resolve(null),
      [project, tk],
    ),
  );
  const applicableMonth = tk
    ? (draft.bill_dt?.slice(0, 7) ??
      stages.data?.items
        .find((s) => s.bill_stg_id === stage)
        ?.bill_plan_dt.slice(0, 7))
    : month;
  const closes = useAdminRead(
    useCallback(
      () =>
        applicableMonth
          ? getMonthClose(applicableMonth, applicableMonth)
          : Promise.resolve([]),
      [applicableMonth],
    ),
  );
  const closed = closes.data?.some((c) => c.status === "CLOSED");
  const write = useAdminWrite(reload);
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  const change = <K extends keyof BillingUpdate>(
    key: K,
    value: BillingUpdate[K],
  ) => {
    if (key === "sply_amt") manualSupply.current = value !== undefined;
    if (key === "vat_amt") manualVat.current = value !== undefined;
    setDirty(true);
    setLocal(null);
    write.clear();
    setDraft((d) => ({ ...d, [key]: value }));
  };
  const error = local ?? write.error;
  const field = (name: string) =>
    error?.field === name ? error.message : null;
  const lines = preview?.details ?? existing?.details ?? [];
  const previewRead = async () => {
    setReading(true);
    setLocal(null);
    try {
      const value = await previewBilling(
        project,
        tk ? stage : undefined,
        tk ? undefined : month,
      );
      setPreview(value);
      setDraft((d) => ({
        ...d,
        sply_amt: manualSupply.current ? d.sply_amt : value.sply_amt,
        vat_amt: manualVat.current ? d.vat_amt : value.suggested_vat_amt,
      }));
    } catch (e) {
      setLocal(failure(e));
    } finally {
      setReading(false);
    }
  };
  const save = () => {
    if (!project || (!row && (tk ? !stage : !month))) {
      setLocal(
        failure(new Error("프로젝트와 청구 단계 또는 귀속월을 선택하세요.")),
      );
      return;
    }
    let saved: BillingDetail;
    void write.save(
      async () => {
        if (row) saved = await updateBilling(row.bill_id!, draft);
        else {
          const input: BillingCreate = {
            project_id: project,
            ...(tk
              ? { stage_id: stage }
              : { month, additional, bill_odr_no: Number(ordinal) }),
            bill_dt: draft.bill_dt,
            ...(draft.sply_amt == null ? {} : { sply_amt: draft.sply_amt }),
            ...(draft.vat_amt == null ? {} : { vat_amt: draft.vat_amt }),
            rcpt_plan_dt: draft.rcpt_plan_dt,
            txinv_no: draft.txinv_no ?? undefined,
            txinv_dt: draft.txinv_dt,
            ...(draft.details ? { details: draft.details } : {}),
          };
          saved = await createBilling(input);
        }
      },
      () => {
        setDirty(false);
        onSaved(saved);
      },
    );
  };
  return (
    <div className={styles.scrim} data-testid="billing-editor-root">
      <aside
        role="dialog"
        aria-modal="true"
        aria-label={row ? "청구 수정" : "청구 등록"}
        className={styles.drawer}
        data-testid="billing-editor"
      >
        <header>
          <h2>{row ? "청구 수정" : "청구 등록"}</h2>
          <Button
            data-testid="billing-editor-close"
            disabled={write.pending}
            onClick={() => (dirty ? setLeaving(true) : onCancel())}
          >
            닫기
          </Button>
        </header>
        <form
          className={styles.form}
          data-testid="billing-editor-form"
          onSubmit={(e) => {
            e.preventDefault();
            save();
          }}
        >
          <ErrorBanner
            error={error}
            onRetry={write.uncertain ? () => void write.recover() : undefined}
          />
          {[projects, selected, stages, closes].map((s, i) => (
            <ErrorBanner key={i} error={s.error} />
          ))}
          {closed && (
            <p className={styles.warning}>마감된 월은 수정할 수 없습니다.</p>
          )}
          {projects.loading ? (
            <Skeleton />
          ) : (
            <SelectInput
              name="project_id"
              label="프로젝트"
              required
              disabled={!!row}
              value={String(project || "")}
              options={(projects.data ?? [])
                .filter((p) => p.type !== "IN")
                .map((p) => ({
                  value: String(p.id),
                  label: `${p.no} · ${p.name}`,
                }))}
              error={field("project_id")}
              onValueChange={(v) => {
                setProject(Number(v));
                setStage(undefined);
                setPreview(null);
                setDirty(true);
              }}
            />
          )}
          {tk ? (
            <SelectInput
              name="stage_id"
              label="TK 미청구 단계"
              required
              disabled={!!row}
              value={String(stage ?? "")}
              options={(stages.data?.items ?? [])
                .filter((s) => !s.billed || s.bill_stg_id === row?.bill_stg_id)
                .map((s) => ({
                  value: String(s.bill_stg_id),
                  label: `${s.stg_odr_no}차 · ${s.bill_stg_nm} · ${s.bill_plan_dt}`,
                }))}
              error={field("stage_id")}
              onValueChange={(v) => {
                setStage(Number(v));
                setPreview(null);
                setDirty(true);
              }}
            />
          ) : (
            <TextInput
              name="month"
              label="귀속월"
              type="month"
              required
              readOnly={!!row}
              value={month}
              error={field("month")}
              onValueChange={(v) => {
                setMonth(v);
                setPreview(null);
                setDirty(true);
              }}
            />
          )}
          {!tk && (
            <div className={styles.fields}>
              <label>
                <input
                  data-testid="billing-additional"
                  type="checkbox"
                  disabled={!!row}
                  checked={row ? (row.bill_odr_no ?? 1) > 1 : additional}
                  onChange={(e) => {
                    setAdditional(e.target.checked);
                    setDirty(true);
                  }}
                />
                추가 청구 명시
              </label>
              <label>
                차수
                <input
                  data-testid="billing-ordinal"
                  value={ordinal}
                  readOnly={!!row}
                  inputMode="numeric"
                  onChange={(e) => {
                    setOrdinal(e.target.value);
                    setDirty(true);
                  }}
                />
              </label>
            </div>
          )}
          {!row && (
            <Button
              type="button"
              data-testid="billing-preview"
              pending={reading}
              disabled={!project || (tk ? !stage : !month)}
              onClick={() => void previewRead()}
            >
              미리보기
            </Button>
          )}
          {(preview?.warnings ?? existing?.warnings ?? []).map((w, i) => (
            <p className={styles.warning} key={i}>
              {w}
            </p>
          ))}
          <BillingLines lines={lines} />
          {selected.data?.stlm_mthd_cd === "HOUR" &&
            lines.map((l) => (
              <MoneyInput
                key={l.asgn_id}
                name={`overtime-${l.asgn_id}`}
                label={`${l.display_name} 초과근무금액`}
                value={
                  draft.details?.find((d) => d.asgn_id === l.asgn_id)
                    ?.ovtm_amt ?? l.ovtm_amt
                }
                onValueChange={(v) =>
                  change("details", [
                    ...(
                      draft.details ??
                      lines.map((x) => ({
                        asgn_id: x.asgn_id,
                        ovtm_amt: x.ovtm_amt,
                      }))
                    ).filter((x) => x.asgn_id !== l.asgn_id),
                    { asgn_id: l.asgn_id, ovtm_amt: v ?? 0 },
                  ])
                }
              />
            ))}
          <div className={styles.fields}>
            <MoneyInput
              name="sply_amt"
              label="공급가액"
              value={draft.sply_amt ?? null}
              error={field("sply_amt")}
              onValueChange={(v) => change("sply_amt", v ?? undefined)}
            />
            <MoneyInput
              name="vat_amt"
              label="부가세"
              value={draft.vat_amt ?? null}
              error={field("vat_amt")}
              onValueChange={(v) => change("vat_amt", v ?? undefined)}
            />
            <TextInput
              name="bill_dt"
              label="청구일"
              type="date"
              value={draft.bill_dt ?? ""}
              error={field("bill_dt")}
              onValueChange={(v) => change("bill_dt", v || null)}
            />
            <TextInput
              name="txinv_no"
              label="세금계산서 번호"
              maxLength={50}
              value={draft.txinv_no ?? ""}
              error={field("txinv_no")}
              onValueChange={(v) => change("txinv_no", v || null)}
            />
            <TextInput
              name="txinv_dt"
              label="작성일"
              type="date"
              value={draft.txinv_dt ?? ""}
              error={field("txinv_dt")}
              onValueChange={(v) => change("txinv_dt", v || null)}
            />
            <TextInput
              name="rcpt_plan_dt"
              label="입금 예정일"
              type="date"
              value={draft.rcpt_plan_dt ?? ""}
              error={field("rcpt_plan_dt")}
              onValueChange={(v) => change("rcpt_plan_dt", v || null)}
            />
          </div>
          <p>
            합계 (읽기 전용):{" "}
            {draft.sply_amt == null || draft.vat_amt == null
              ? "자동 계산"
              : (draft.sply_amt + draft.vat_amt).toLocaleString("ko-KR")}
            원
          </p>
          {lines.length > 0 &&
            draft.sply_amt !== undefined &&
            draft.sply_amt !==
              lines.reduce((sum, l) => sum + l.bill_amt, 0) && (
              <p className={styles.warning}>
                공급가액과 상세 합계가 다릅니다. 확인 후 확정할 수 있습니다.
              </p>
            )}
          {row && (
            <>
              <TextareaInput
                name="rmrk_desc"
                label="비고"
                maxLength={500}
                value={draft.rmrk_desc ?? ""}
                error={field("rmrk_desc")}
                onValueChange={(v) => change("rmrk_desc", v)}
              />
              <label>
                <input
                  data-testid="billing-recalculate"
                  type="checkbox"
                  checked={draft.recalculate ?? false}
                  onChange={(e) => change("recalculate", e.target.checked)}
                />
                원천 상세 다시 계산 (명시 요청)
              </label>
            </>
          )}
          <div className={styles.actions}>
            {onAttendance &&
              selected.data?.cntrct_ty_cd === "DP" &&
              ["DAY", "HOUR"].includes(selected.data.stlm_mthd_cd ?? "") && (
                <Button
                  type="button"
                  data-testid="billing-attendance"
                  onClick={() => onAttendance(project, month)}
                >
                  파견 근태
                </Button>
              )}
            <Button
              type="button"
              data-testid="billing-editor-cancel"
              disabled={write.pending}
              onClick={() => (dirty ? setLeaving(true) : onCancel())}
            >
              취소
            </Button>
            <Button
              type="submit"
              data-testid="billing-editor-save"
              variant="primary"
              pending={write.pending}
              disabled={
                !!closed ||
                write.uncertain ||
                selected.loading ||
                closes.loading ||
                !!selected.error ||
                !!closes.error ||
                (preview?.missing_price_assignments.length ?? 0) > 0
              }
            >
              저장
            </Button>
          </div>
        </form>
        {leaving && (
          <ConfirmDialog
            title="작성 중인 내용을 버릴까요?"
            confirmLabel="나가기"
            cancelLabel="계속 작성"
            onCancel={() => setLeaving(false)}
            onConfirm={onCancel}
          >
            나가면 입력한 내용이 사라집니다.
          </ConfirmDialog>
        )}
      </aside>
    </div>
  );
}
