import { useEffect, useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { readData, type ApiFailure } from "../../shared/api";
import { listProjectOptions, type ProjectOption } from "../projects/client";
import { seoulMonth } from "../shared/PeriodBar";
import {
  billingFailure,
  previewBilling,
  writeBilling,
  type BillingDetail,
  type Preview,
} from "./client";
import styles from "../developers/Developers.module.css";

export function UI13M1({
  detail,
  projectId,
  month,
  onClose,
  onSaved,
  onAttendance,
}: {
  detail?: BillingDetail;
  projectId?: number;
  month?: string;
  onClose: () => void;
  onSaved: () => Promise<unknown>;
  onAttendance: () => void;
}) {
  const [projects, setProjects] = useState<ProjectOption[]>([]);
  const [project, setProject] = useState(
    String(detail?.billing.prj_id ?? projectId ?? ""),
  );
  const [selectedMonth, setMonth] = useState(
    detail?.billing.atrb_ym ?? month ?? seoulMonth(),
  );
  const [stage, setStage] = useState(String(detail?.billing.bill_stg_id ?? ""));
  const [stages, setStages] = useState<
    { bill_stg_id: number; bill_stg_nm: string; billed: boolean }[]
  >([]);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [values, setValues] = useState({
    sply_amt: String(detail?.billing.sply_amt ?? ""),
    vat_amt: String(detail?.billing.vat_amt ?? ""),
    bill_dt: detail?.billing.bill_dt ?? "",
    txinv_no: detail?.billing.txinv_no ?? "",
    txinv_dt: detail?.billing.txinv_dt ?? "",
    rcpt_plan_dt: detail?.billing.rcpt_plan_dt ?? "",
  });
  const [overtime, setOvertime] = useState<Record<number, string>>({});
  const [additional, setAdditional] = useState(false),
    [order, setOrder] = useState("2");
  const [error, setError] = useState<ApiFailure | null>(null),
    [pending, setPending] = useState(false),
    [dirty, setDirty] = useState(false);
  const type =
    detail?.billing.contract_type ??
    projects.find((p) => String(p.id) === project)?.type;
  const close = () => {
    if (!pending && (!dirty || window.confirm("입력을 버리고 닫을까요?")))
      onClose();
  };
  useEffect(() => {
    listProjectOptions()
      .then(setProjects)
      .catch((e) => setError(billingFailure(e)));
  }, []);
  useEffect(() => {
    if (!project || type !== "TK" || detail) return;
    readData<{ items: typeof stages }>(`/api/projects/${project}/bill-stages`)
      .then((r) => setStages(r.items))
      .catch((e) => setError(billingFailure(e)));
  }, [project, type, detail]);
  const loadPreview = async () => {
    setPending(true);
    setError(null);
    try {
      const result = await previewBilling({
        projectId: project,
        ...(type === "TK" ? { stageId: stage } : { month: selectedMonth }),
      });
      setPreview(result);
      setValues((v) => ({
        ...v,
        sply_amt: String(result.sply_amt),
        vat_amt: String(result.suggested_vat_amt),
      }));
      setDirty(true);
    } catch (e) {
      setError(billingFailure(e));
    } finally {
      setPending(false);
    }
  };
  return (
    <Modal title={detail ? "청구 수정" : "청구 등록"} onCancel={close}>
      <form
        data-testid="billing-editor-dialog"
        className={styles.form}
        onSubmit={async (e) => {
          e.preventDefault();
          if (pending) return;
          const supply = Number(values.sply_amt),
            vat = Number(values.vat_amt);
          if (
            !Number.isSafeInteger(supply) ||
            !Number.isSafeInteger(vat) ||
            !values.sply_amt ||
            !values.vat_amt
          ) {
            setError(billingFailure(new Error()));
            return;
          }
          setPending(true);
          setError(null);
          try {
            const body = {
              ...values,
              sply_amt: supply,
              vat_amt: vat,
              bill_dt: values.bill_dt || null,
              txinv_dt: values.txinv_dt || null,
              rcpt_plan_dt: values.rcpt_plan_dt || null,
              details: Object.entries(overtime).map(([id, amount]) => ({
                asgn_id: Number(id),
                ovtm_amt: Number(amount),
              })),
            };
            await writeBilling(
              detail
                ? `/api/billings/${detail.billing.bill_id}`
                : "/api/billings",
              detail ? "PUT" : "POST",
              detail
                ? body
                : {
                    ...body,
                    project_id: Number(project),
                    ...(type === "TK"
                      ? { stage_id: Number(stage) }
                      : {
                          month: selectedMonth,
                          additional,
                          bill_odr_no: additional ? Number(order) : 1,
                        }),
                  },
            );
            await onSaved();
            onClose();
          } catch (e) {
            setError(billingFailure(e));
          } finally {
            setPending(false);
          }
        }}
      >
        <label>
          프로젝트
          <select
            data-testid="billing-editor-project"
            value={project}
            disabled={!!detail}
            required
            onChange={(e) => {
              setProject(e.target.value);
              setStage("");
              setPreview(null);
              setDirty(true);
            }}
          >
            <option value="">선택</option>
            {projects
              .filter((p) => p.type !== "IN")
              .map((p) => (
                <option key={p.id} value={p.id}>
                  {p.no} {p.name}
                </option>
              ))}
          </select>
        </label>
        {type === "TK" ? (
          <label>
            TK 미청구 단계
            <select
              data-testid="billing-editor-stage"
              value={stage}
              required
              disabled={!!detail}
              onChange={(e) => {
                setStage(e.target.value);
                setDirty(true);
              }}
            >
              <option value="">선택</option>
              {stages
                .filter((s) => !s.billed)
                .map((s) => (
                  <option key={s.bill_stg_id} value={s.bill_stg_id}>
                    {s.bill_stg_nm}
                  </option>
                ))}
            </select>
          </label>
        ) : (
          <label>
            귀속월
            <input
              data-testid="billing-editor-month"
              type="month"
              value={selectedMonth}
              required
              disabled={!!detail}
              onChange={(e) => {
                setMonth(e.target.value);
                setDirty(true);
              }}
            />
          </label>
        )}
        {!detail && type !== "TK" && (
          <>
            <label>
              <input
                data-testid="billing-editor-additional"
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
                  data-testid="billing-editor-order"
                  type="number"
                  min={2}
                  value={order}
                  onChange={(e) => setOrder(e.target.value)}
                />
              </label>
            )}
          </>
        )}
        {!detail && (
          <Button
            data-testid="billing-editor-preview"
            type="button"
            pending={pending}
            onClick={() => void loadPreview()}
          >
            미리보기
          </Button>
        )}
        {(preview?.warnings ?? detail?.warnings ?? []).map((w, i) => (
          <p key={i}>{w}</p>
        ))}
        {(preview?.details ?? detail?.details ?? []).map((line, i) => (
          <div key={line.asgn_id ?? i} data-testid={`billing-editor-line-${i}`}>
            {line.display_name} · MM {line.mm_qty ?? "–"} · 단가{" "}
            {line.untprc_amt ?? "–"} · 금액 {line.bill_amt}
            {line.asgn_id != null && (
              <label>
                초과근무금액
                <input
                  data-testid={`billing-editor-overtime-${i}`}
                  inputMode="numeric"
                  value={overtime[line.asgn_id] ?? String(line.ovtm_amt)}
                  onChange={(e) => {
                    setOvertime({
                      ...overtime,
                      [line.asgn_id!]: e.target.value,
                    });
                    setDirty(true);
                  }}
                />
              </label>
            )}
          </div>
        ))}
        {(
          [
            ["sply_amt", "공급가액"],
            ["vat_amt", "부가세"],
            ["bill_dt", "청구일"],
            ["txinv_no", "세금계산서 번호"],
            ["txinv_dt", "작성일"],
            ["rcpt_plan_dt", "입금 예정일"],
          ] as const
        ).map(([key, label]) => (
          <label key={key}>
            {label}
            <input
              data-testid={`billing-editor-${key.replaceAll("_", "-")}`}
              type={key.endsWith("dt") ? "date" : "text"}
              value={values[key]}
              aria-invalid={error?.field === key}
              onChange={(e) => {
                setValues({ ...values, [key]: e.target.value });
                setDirty(true);
              }}
            />
          </label>
        ))}
        <output>
          합계{" "}
          {(Number(values.sply_amt) + Number(values.vat_amt)).toLocaleString(
            "ko-KR",
          )}
          원
        </output>
        {preview && Number(values.sply_amt) !== preview.sply_amt && (
          <p>공급가액과 상세합이 다릅니다. 경고 후 확정할 수 있습니다.</p>
        )}
        {error && <ErrorBanner error={error} />}
        <div className={styles.actions}>
          {type === "DP" && (
            <Button
              data-testid="billing-editor-attendance"
              type="button"
              onClick={onAttendance}
            >
              파견 근태
            </Button>
          )}
          <Button
            data-testid="billing-editor-close"
            type="button"
            onClick={close}
          >
            취소
          </Button>
          <Button
            data-testid="billing-editor-save"
            type="submit"
            pending={pending}
            disabled={
              !detail &&
              (!preview || preview.missing_price_assignments.length > 0)
            }
          >
            저장
          </Button>
        </div>
      </form>
    </Modal>
  );
}
