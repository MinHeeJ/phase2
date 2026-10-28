import { useEffect, useState } from "react";
import { Button, Modal, Badge } from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import { listProjectOptions, type ProjectOption } from "../projectsops/client";
import {
  listProjectBillStages,
  type ProjectBillStage,
} from "../billstages/client";
import {
  previewBilling,
  writeBilling,
  cancelBilling,
  createBillingAmendment,
  confirmBilling,
  type BillingDetail,
  type BillingPreview,
  type BillingReceipt,
} from "./client";
import table from "../developers/Developers.module.css";

export type BillingDialogMode =
  | "create"
  | "edit"
  | "amend"
  | "cancel"
  | "confirm"
  | "receipt"
  | "receipt-edit"
  | "receipt-delete";
export interface BillingDialogProps {
  mode: BillingDialogMode;
  detail?: BillingDetail;
  receipt?: BillingReceipt;
  projectId?: number;
  month?: string;
  stageId?: number;
  onClose: () => void;
  onSaved: (detail?: BillingDetail) => Promise<unknown>;
}
export function BillingDialog(props: BillingDialogProps) {
  const { mode, detail, receipt } = props;
  const [projects, setProjects] = useState<ProjectOption[]>([]);
  const [stages, setStages] = useState<ProjectBillStage[]>([]);
  const [projectId, setProjectId] = useState(
    String(props.projectId ?? detail?.billing.prj_id ?? ""),
  );
  const [stageId, setStageId] = useState(
    String(props.stageId ?? detail?.billing.bill_stg_id ?? ""),
  );
  const [month, setMonth] = useState(
    props.month ?? detail?.billing.atrb_ym ?? "",
  );
  const [supply, setSupply] = useState(
    mode === "amend" ? "" : String(detail?.billing.sply_amt ?? ""),
  );
  const [vat, setVat] = useState(
    mode === "amend" ? "" : String(detail?.billing.vat_amt ?? ""),
  );
  const [date, setDate] = useState(detail?.billing.bill_dt ?? "");
  const [due, setDue] = useState(detail?.billing.rcpt_plan_dt ?? "");
  const [tax, setTax] = useState(
    mode === "amend" ? "" : (detail?.billing.txinv_no ?? ""),
  );
  const [taxDate, setTaxDate] = useState(
    mode === "amend" ? "" : (detail?.billing.txinv_dt ?? ""),
  );
  const [reason, setReason] = useState("");
  const [receiptDate, setReceiptDate] = useState(receipt?.rcpt_dt ?? "");
  const [receiptAmount, setReceiptAmount] = useState(
    String(receipt?.rcpt_amt ?? ""),
  );
  const [note, setNote] = useState(receipt?.rmrk_desc ?? "");
  const [additional, setAdditional] = useState(false),
    [ordinal, setOrdinal] = useState("");
  const [preview, setPreview] = useState<BillingPreview | null>(null);
  const [error, setError] = useState(""),
    [field, setField] = useState<string | null>(null);
  const [pending, setPending] = useState(false),
    [dirty, setDirty] = useState(false),
    [discard, setDiscard] = useState(false);
  const selected = projects.find((p) => p.id === Number(projectId));
  const receiptMode = mode.startsWith("receipt");
  const moneyForm = ["create", "edit", "amend"].includes(mode);
  useEffect(() => {
    if (mode === "create")
      void listProjectOptions()
        .then((p) => setProjects(p.filter((p) => p.type !== "IN")))
        .catch(fail);
  }, [mode]);
  useEffect(() => {
    if (mode === "create" && selected?.type === "TK")
      void listProjectBillStages(selected.id)
        .then((s) => setStages(s.items.filter((s) => !s.billed)))
        .catch(fail);
  }, [projectId, selected?.type, mode]);
  function fail(e: unknown) {
    setError(e instanceof Error ? e.message : "처리하지 못했습니다.");
    setField(e instanceof ApiFailure ? e.field : null);
  }
  const close = () => {
    if (pending) return;
    if (dirty) setDiscard(true);
    else props.onClose();
  };
  async function loadPreview() {
    setPending(true);
    setError("");
    try {
      const value = await previewBilling({
        projectId: Number(projectId),
        ...(selected?.type === "TK" ? { stageId: Number(stageId) } : { month }),
      });
      setPreview(value);
      setSupply(String(value.sply_amt));
      setVat(String(value.suggested_vat_amt));
      setDue(value.rcpt_plan_dt ?? "");
      setDirty(true);
    } catch (e) {
      fail(e);
    } finally {
      setPending(false);
    }
  }
  async function save() {
    setError("");
    setField(null);
    if ((mode === "cancel" || mode === "amend") && !reason.trim()) {
      setError("사유를 입력하세요.");
      setField("reason");
      return;
    }
    if (
      moneyForm &&
      (!supply ||
        !vat ||
        !Number.isSafeInteger(Number(supply)) ||
        !Number.isSafeInteger(Number(vat)))
    ) {
      setError("원 단위 정수 금액을 입력하세요.");
      return;
    }
    if (mode === "amend" && (Number(supply) >= 0 || Number(vat) > 0)) {
      setError("수정 공급가는 음수, 부가세는 0 이하여야 합니다.");
      return;
    }
    if (
      receiptMode &&
      mode !== "receipt-delete" &&
      (!receiptDate ||
        !Number.isSafeInteger(Number(receiptAmount)) ||
        Number(receiptAmount) <= 0)
    ) {
      setError("입금일과 양수 입금액을 입력하세요.");
      return;
    }
    if (
      mode === "create" &&
      (!projectId || (selected?.type === "TK" ? !stageId : !month))
    ) {
      setError("프로젝트와 단계 또는 귀속월을 선택하세요.");
      return;
    }
    if (
      mode === "create" &&
      (!preview || preview.missing_price_assignments.length)
    ) {
      setError("미리보기에서 단가와 원천을 확인하세요.");
      return;
    }
    setPending(true);
    try {
      let saved: BillingDetail | undefined;
      const id = detail?.billing.bill_id;
      const body = {
        bill_dt: date || null,
        sply_amt: Number(supply),
        vat_amt: Number(vat),
        rcpt_plan_dt: due || null,
        txinv_no: tax || null,
        txinv_dt: taxDate || null,
      };
      if (mode === "create")
        saved = await writeBilling("/api/billings", {
          ...body,
          project_id: Number(projectId),
          ...(selected?.type === "TK"
            ? { stage_id: Number(stageId) }
            : {
                month,
                additional,
                ...(additional ? { bill_odr_no: Number(ordinal) } : {}),
              }),
        });
      else if (id) {
        if (mode === "amend")
          saved = await createBillingAmendment(id, { ...body, reason });
        else if (mode === "cancel") saved = await cancelBilling(id, reason);
        else if (mode === "confirm") saved = await confirmBilling(id);
        else if (mode === "edit")
          saved = await writeBilling(`/api/billings/${id}`, body, "PUT");
        else {
          const path = `/api/billings/${id}/receipts${receipt ? `/${receipt.receipt_id}` : ""}`;
          await writeBilling(
            path,
            mode === "receipt-delete"
              ? undefined
              : {
                  rcpt_dt: receiptDate,
                  rcpt_amt: Number(receiptAmount),
                  rmrk_desc: note || null,
                },
            mode === "receipt-delete"
              ? "DELETE"
              : mode === "receipt-edit"
                ? "PUT"
                : "POST",
          );
        }
      }
      await props.onSaved(saved);
      props.onClose();
    } catch (e) {
      fail(e);
    } finally {
      setPending(false);
    }
  }
  const title = {
    create: "청구 등록",
    edit: "청구 수정",
    amend: "수정 청구",
    cancel: "청구 취소",
    confirm: "청구 확정",
    receipt: "입금 등록",
    "receipt-edit": "입금 수정",
    "receipt-delete": "입금 삭제",
  }[mode];
  const input = (
    label: string,
    value: string,
    setter: (v: string) => void,
    name: string,
    type = "text",
  ) => (
    <label>
      {label}
      <input
        data-testid={`billing-dialog-${name}`}
        aria-invalid={field === name}
        type={type}
        value={value}
        onChange={(e) => {
          setter(e.target.value);
          setDirty(true);
        }}
      />
    </label>
  );
  return (
    <Modal
      title={title}
      onCancel={close}
      confirmation={
        mode === "cancel" || mode === "confirm" || mode === "receipt-delete"
      }
    >
      <section data-testid={`billing-dialog-${mode}`} className={table.form}>
        {detail && (
          <p>
            원본 청구: {detail.billing.bill_no} · {detail.billing.project_name}{" "}
            · 조정 총액{" "}
            {detail.billing.adjusted_total_amt.toLocaleString("ko-KR")}원 · 누적
            입금 {detail.billing.received_amt.toLocaleString("ko-KR")}원
          </p>
        )}
        {mode === "create" && (
          <>
            <label>
              프로젝트
              <select
                data-testid="billing-dialog-project"
                value={projectId}
                onChange={(e) => {
                  setProjectId(e.target.value);
                  setStageId("");
                  setPreview(null);
                  setDirty(true);
                }}
              >
                <option value="">선택</option>
                {projects.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </label>
            {selected?.type === "TK" ? (
              <label>
                TK 미청구 단계
                <select
                  data-testid="billing-dialog-stage"
                  value={stageId}
                  onChange={(e) => {
                    setStageId(e.target.value);
                    setPreview(null);
                  }}
                >
                  <option value="">선택</option>
                  {stages.map((s) => (
                    <option key={s.bill_stg_id} value={s.bill_stg_id}>
                      {s.bill_stg_nm}
                    </option>
                  ))}
                </select>
              </label>
            ) : (
              <>
                {input(
                  "귀속월",
                  month,
                  (v) => {
                    setMonth(v);
                    setPreview(null);
                  },
                  "month",
                  "month",
                )}
                <label>
                  <input
                    data-testid="billing-dialog-additional"
                    type="checkbox"
                    checked={additional}
                    onChange={(e) => setAdditional(e.target.checked)}
                  />
                  추가 청구 명시
                </label>
                {additional &&
                  input("차수", ordinal, setOrdinal, "ordinal", "number")}
              </>
            )}
            <Button
              data-testid="billing-dialog-preview"
              pending={pending}
              onClick={() => void loadPreview()}
            >
              미리보기
            </Button>
          </>
        )}
        {mode === "edit" && (
          <p>프로젝트 · 귀속월 · 단계는 변경할 수 없습니다.</p>
        )}
        {moneyForm && (
          <div className={table.fields}>
            {input("공급가액", supply, setSupply, "sply_amt", "number")}
            {input("부가세", vat, setVat, "vat_amt", "number")}
            <p>
              합계 (읽기 전용):{" "}
              {(Number(supply) + Number(vat)).toLocaleString("ko-KR")}원
            </p>
            {input("청구일", date, setDate, "bill_dt", "date")}
            {input("입금 예정일", due, setDue, "rcpt_plan_dt", "date")}
            {input("세금계산서 번호", tax, setTax, "txinv_no")}
            {input(
              "세금계산서 작성일",
              taxDate,
              setTaxDate,
              "txinv_dt",
              "date",
            )}
          </div>
        )}
        {(mode === "cancel" || mode === "amend") &&
          input("사유", reason, setReason, "reason")}
        {mode === "confirm" && (
          <p>
            확정 후 상세 스냅샷은 변경할 수 없습니다. 세금계산서는 실제 발행되지
            않습니다.
          </p>
        )}
        {mode === "receipt-delete" ? (
          <p>
            선택 입금 {receipt?.rcpt_dt} ·{" "}
            {receipt?.rcpt_amt.toLocaleString("ko-KR")}원을 삭제하시겠습니까?
          </p>
        ) : (
          receiptMode && (
            <>
              {input("입금일", receiptDate, setReceiptDate, "rcpt_dt", "date")}
              {input(
                "입금액 (부가세 포함)",
                receiptAmount,
                setReceiptAmount,
                "rcpt_amt",
                "number",
              )}
              {input("비고", note, setNote, "rmrk_desc")}
            </>
          )
        )}
        {preview && (
          <>
            <BillingLines lines={preview.details} />
            {preview.warnings.map((w) => (
              <p key={w}>
                <Badge tone="warning">{w}</Badge>
              </p>
            ))}
          </>
        )}
        {error && (
          <p role="alert" data-testid="billing-dialog-error">
            {field ? `${field}: ` : ""}
            {error}
          </p>
        )}
        <div className={table.actions}>
          <Button
            data-testid="billing-dialog-close"
            disabled={pending}
            onClick={close}
          >
            닫기
          </Button>
          <Button
            data-testid="billing-dialog-save"
            variant="primary"
            pending={pending}
            onClick={() => void save()}
          >
            {mode === "cancel"
              ? "청구 취소 확인"
              : mode === "confirm"
                ? "확정 확인"
                : mode === "receipt-delete"
                  ? "삭제 확인"
                  : "저장"}
          </Button>
        </div>
        {discard && (
          <div role="alertdialog" data-testid="billing-discard">
            <p>입력 내용을 버리시겠습니까?</p>
            <Button
              data-testid="billing-discard-keep"
              onClick={() => setDiscard(false)}
            >
              계속 작성
            </Button>
            <Button data-testid="billing-discard-yes" onClick={props.onClose}>
              버리기
            </Button>
          </div>
        )}
      </section>
    </Modal>
  );
}
export function BillingLines({ lines }: { lines: BillingPreview["details"] }) {
  return (
    <div className={table.tableScroll}>
      <table className={table.table}>
        <thead>
          <tr>
            {[
              "표기 인력",
              "귀속월",
              "MM",
              "⑦ 투입 MM",
              "단가",
              "근무일수",
              "정규시간",
              "초과금액",
              "금액",
            ].map((t) => (
              <th key={t}>{t}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {lines.map((l, i) => (
            <tr
              key={`${l.asgn_id}-${l.work_ym}-${i}`}
              data-testid={`billing-line-${l.asgn_id ?? i}`}
            >
              <td>{l.display_name ?? "수정 청구"}</td>
              <td>{l.work_ym}</td>
              <td>{l.mm_qty ?? "—"}</td>
              <td>{l.source_mm_qty ?? "—"}</td>
              <td>{l.untprc_amt?.toLocaleString("ko-KR") ?? "—"}</td>
              <td>{l.work_day_qty ?? "—"}</td>
              <td>{l.work_tm_qty ?? "—"}</td>
              <td>{l.ovtm_amt.toLocaleString("ko-KR")}</td>
              <td>{l.bill_amt.toLocaleString("ko-KR")}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
