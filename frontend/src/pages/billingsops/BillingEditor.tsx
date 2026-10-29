import { useEffect, useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { readData } from "../../shared/api";
import { listProjectOptions, type ProjectOption } from "../projectsops/client";
import { billingMoney } from "./BillingDetailPanel";
import * as client from "./client";
import type { BillingDetail, BillingPreview } from "./client";
import table from "../developers/Developers.module.css";
import styles from "./BillingReview.module.css";

interface StageOption {
  bill_stg_id: number;
  bill_stg_nm: string;
  sply_amt: number;
  bill_plan_dt: string;
  billed: boolean;
}
export function BillingEditor({
  detail,
  initial,
  onClose,
  onSaved,
}: {
  detail?: BillingDetail;
  initial?: { projectId: number; month: string };
  onClose: () => void;
  onSaved: (d: BillingDetail) => Promise<void>;
}) {
  const [projects, setProjects] = useState<ProjectOption[]>([]),
    [stages, setStages] = useState<StageOption[]>([]);
  const [project, setProject] = useState(
    String(detail?.billing.prj_id ?? initial?.projectId ?? ""),
  );
  const [month, setMonth] = useState(
    detail?.billing.atrb_ym ?? initial?.month ?? "",
  );
  const [stage, setStage] = useState(String(detail?.billing.bill_stg_id ?? ""));
  const [supply, setSupply] = useState(
    detail ? String(detail.billing.sply_amt) : "",
  );
  const [vat, setVat] = useState(detail ? String(detail.billing.vat_amt) : "");
  const [date, setDate] = useState(detail?.billing.bill_dt ?? ""),
    [due, setDue] = useState(detail?.billing.rcpt_plan_dt ?? "");
  const [invoice, setInvoice] = useState(detail?.billing.txinv_no ?? ""),
    [invoiceDate, setInvoiceDate] = useState(detail?.billing.txinv_dt ?? "");
  const [additional, setAdditional] = useState(false),
    [order, setOrder] = useState("1");
  const [preview, setPreview] = useState<BillingPreview | null>(null),
    [pending, setPending] = useState(false),
    [error, setError] = useState("");
  const [overtime, setOvertime] = useState<Record<number, string>>({});
  const [dirty, setDirty] = useState(false),
    [discard, setDiscard] = useState(false);
  const type =
    detail?.billing.contract_type ??
    projects.find((p) => p.id === Number(project))?.type;
  useEffect(() => {
    listProjectOptions()
      .then((p) => setProjects(p.filter((p) => p.type !== "IN")))
      .catch((e) => setError(e.message));
  }, []);
  useEffect(() => {
    if (type === "TK" && project)
      readData<{ items: StageOption[] }>(
        `/api/projects/${encodeURIComponent(project)}/bill-stages`,
      )
        .then((d) => setStages(d.items))
        .catch((e) => setError(e.message));
  }, [project, type]);
  async function loadPreview() {
    setPending(true);
    setError("");
    try {
      const p = await client.previewBilling({
        projectId: project,
        month: type === "TK" ? undefined : month,
        stageId: type === "TK" ? stage : undefined,
      });
      setPreview(p);
      if (supply === "") setSupply(String(p.sply_amt));
      if (vat === "") setVat(String(p.suggested_vat_amt));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPending(false);
    }
  }
  async function save() {
    if (
      !project ||
      (type === "TK" ? !stage && !detail : !month) ||
      supply === "" ||
      vat === "" ||
      !Number.isSafeInteger(Number(supply)) ||
      !Number.isSafeInteger(Number(vat)) ||
      Number(supply) < 0 ||
      Number(vat) < 0
    ) {
      setError("필수 항목과 원 단위 정수 금액을 확인하세요.");
      return;
    }
    if (preview?.missing_price_assignments.length) {
      setError("단가가 없는 투입은 청구를 생성할 수 없습니다.");
      return;
    }
    setPending(true);
    setError("");
    try {
      await onSaved(
        await client.saveBilling(detail?.billing.bill_id ?? null, {
          ...(detail
            ? {}
            : {
                project_id: Number(project),
                ...(type === "TK" ? { stage_id: Number(stage) } : { month }),
                additional,
                bill_odr_no: Number(order),
              }),
          bill_dt: date || null,
          sply_amt: Number(supply),
          vat_amt: Number(vat),
          rcpt_plan_dt: due || null,
          txinv_no: invoice || null,
          txinv_dt: invoiceDate || null,
          ...(Object.keys(overtime).length
            ? {
                details: Object.entries(overtime).map(([id, amount]) => ({
                  asgn_id: Number(id),
                  ovtm_amt: Number(amount),
                })),
              }
            : {}),
        }),
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPending(false);
    }
  }
  return (
    <Modal
      title={detail ? "청구 수정" : "청구 등록"}
      onCancel={() => (dirty ? setDiscard(true) : onClose())}
    >
      <section data-testid="billing-editor" onChange={() => setDirty(true)}>
        <div className={styles.fields}>
          <label>
            프로젝트
            <select
              data-testid="billing-project"
              disabled={!!detail}
              value={project}
              onChange={(e) => {
                setProject(e.target.value);
                setStage("");
                setPreview(null);
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
          {type === "TK" ? (
            <label>
              청구 단계
              <select
                data-testid="billing-stage"
                disabled={!!detail}
                value={stage}
                onChange={(e) => setStage(e.target.value)}
              >
                <option value="">선택</option>
                {stages
                  .filter(
                    (s) =>
                      !s.billed ||
                      s.bill_stg_id === detail?.billing.bill_stg_id,
                  )
                  .map((s) => (
                    <option key={s.bill_stg_id} value={s.bill_stg_id}>
                      {s.bill_stg_nm} · {s.bill_plan_dt ?? "예정일 미입력"}
                    </option>
                  ))}
              </select>
            </label>
          ) : (
            <label>
              귀속월
              <input
                data-testid="billing-month"
                type="month"
                disabled={!!detail}
                value={month}
                onChange={(e) => setMonth(e.target.value)}
              />
            </label>
          )}
          {!detail && type !== "TK" && (
            <>
              <label>
                추가 청구
                <input
                  data-testid="billing-additional"
                  type="checkbox"
                  checked={additional}
                  onChange={(e) => setAdditional(e.target.checked)}
                />
              </label>
              <label>
                차수
                <input
                  data-testid="billing-order"
                  type="number"
                  min={1}
                  value={order}
                  onChange={(e) => setOrder(e.target.value)}
                />
              </label>
            </>
          )}
          {[
            ["공급가액", supply, setSupply, "number"],
            ["부가세", vat, setVat, "number"],
            ["청구일", date, setDate, "date"],
            ["입금 예정일", due, setDue, "date"],
            ["세금계산서 번호", invoice, setInvoice, "text"],
            ["작성일", invoiceDate, setInvoiceDate, "date"],
          ].map(([label, value, set, type], i) => (
            <label key={i}>
              {label as string}
              <input
                data-testid={`billing-editor-field-${i}`}
                type={type as string}
                value={value as string}
                onChange={(e) => (set as (s: string) => void)(e.target.value)}
              />
            </label>
          ))}
        </div>
        <p>합계 (읽기 전용): {billingMoney(Number(supply) + Number(vat))}원</p>
        <Button
          data-testid="billing-preview"
          pending={pending}
          onClick={() => void loadPreview()}
        >
          미리보기
        </Button>
        {preview && (
          <>
            <div className={table.tableScroll}>
              <table className={table.table}>
                <thead>
                  <tr>
                    <th>표기 인력</th>
                    <th>MM</th>
                    <th>단가</th>
                    <th>초과근무금액</th>
                    <th>상세 금액</th>
                  </tr>
                </thead>
                <tbody>
                  {preview.details.map((l) => (
                    <tr
                      key={l.asgn_id}
                      data-testid={`billing-preview-line-${l.asgn_id}`}
                    >
                      <td>{l.display_name}</td>
                      <td>{l.mm_qty ?? "–"}</td>
                      <td>{billingMoney(l.untprc_amt)}</td>
                      <td>
                        <input
                          data-testid={`billing-overtime-${l.asgn_id}`}
                          aria-label={`${l.display_name} 초과근무금액`}
                          type="number"
                          min={0}
                          value={overtime[l.asgn_id] ?? String(l.ovtm_amt)}
                          onChange={(e) =>
                            setOvertime({
                              ...overtime,
                              [l.asgn_id]: e.target.value,
                            })
                          }
                        />
                      </td>
                      <td>{billingMoney(l.bill_amt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {preview.warnings.map((w, i) => (
              <p className={styles.message} key={i}>
                {w}
              </p>
            ))}
            {Number(supply) !== preview.sply_amt && (
              <p className={styles.message}>
                원천 상세 합과 수동 공급가액이 다릅니다. 경고 후 확정할 수
                있습니다.
              </p>
            )}
          </>
        )}
        {error && <p role="alert">{error}</p>}
        <div className={table.actions}>
          <Button
            data-testid="billing-editor-close"
            disabled={pending}
            onClick={() => (dirty ? setDiscard(true) : onClose())}
          >
            취소
          </Button>
          <Button
            data-testid="billing-editor-save"
            pending={pending}
            variant="primary"
            onClick={() => void save()}
          >
            저장
          </Button>
        </div>
        {discard && (
          <div role="alertdialog" data-testid="billing-discard">
            <p>입력한 내용을 버리시겠습니까?</p>
            <Button
              data-testid="billing-keep"
              onClick={() => setDiscard(false)}
            >
              계속 입력
            </Button>
            <Button data-testid="billing-discard-confirm" onClick={onClose}>
              버리기
            </Button>
          </div>
        )}
      </section>
    </Modal>
  );
}
