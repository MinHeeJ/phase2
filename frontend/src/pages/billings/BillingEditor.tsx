import { useEffect, useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { ApiFailure } from "../../shared/api";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { seoulMonth } from "../shared/PeriodBar";
import { listProjectOptions, type ProjectOption } from "../projects/client";
import {
  listProjectBillStages,
  type ProjectBillStage,
} from "../billstages/client";
import {
  createBilling,
  previewBilling,
  updateBilling,
  type BillingDetail,
  type BillingRow,
  type BillingPreview,
  type BillingUpdate,
} from "./client";
import tables from "../developers/Developers.module.css";
import styles from "./Billings.module.css";

interface Props {
  detail?: BillingDetail;
  initial?: Partial<BillingRow>;
  year: number;
  onClose: () => void;
  onSaved: (detail: BillingDetail) => void;
  onAttendance?: (projectId: number, month: string) => void;
}
export function BillingEditor({
  detail,
  initial,
  year,
  onClose,
  onSaved,
  onAttendance,
}: Props) {
  const row = detail?.billing ?? initial;
  const editing = !!detail;
  const [projects, setProjects] = useState<ProjectOption[]>([]);
  const [stages, setStages] = useState<ProjectBillStage[]>([]);
  const [projectId, setProjectId] = useState(
    row?.prj_id ? String(row.prj_id) : "",
  );
  const [stageId, setStageId] = useState(
    row?.bill_stg_id ? String(row.bill_stg_id) : "",
  );
  const [month, setMonth] = useState(
    row?.atrb_ym ?? `${year}-${seoulMonth().slice(5)}`,
  );
  const [additional, setAdditional] = useState(false),
    [order, setOrder] = useState("");
  const [values, setValues] = useState({
    bill_dt: row?.bill_dt ?? "",
    sply_amt: editing ? String(row!.sply_amt) : "",
    vat_amt: editing ? String(row!.vat_amt) : "",
    rcpt_plan_dt: row?.rcpt_plan_dt ?? "",
    txinv_no: row?.txinv_no ?? "",
    txinv_dt: row?.txinv_dt ?? "",
    rmrk_desc: "",
  });
  const [remarkEdited, setRemarkEdited] = useState(false);
  const [recalculate, setRecalculate] = useState(false);
  const [preview, setPreview] = useState<BillingPreview | null>(null);
  const [overtime, setOvertime] = useState<Record<number, string>>({});
  const [pending, setPending] = useState(false),
    [loadingOptions, setLoadingOptions] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null);
  const project = projects.find((p) => p.id === Number(projectId));
  const type = editing
    ? row?.contract_type
    : (project?.type ??
      (Number(projectId) === row?.prj_id ? row.contract_type : undefined));
  useEffect(() => {
    if (editing) return;
    let live = true;
    setLoadingOptions(true);
    listProjectOptions()
      .then((rows) => {
        if (live) setProjects(rows.filter((p) => p.type !== "IN"));
      })
      .catch((e: ApiFailure) => {
        if (live) setError(e);
      })
      .finally(() => {
        if (live) setLoadingOptions(false);
      });
    return () => {
      live = false;
    };
  }, [editing]);
  useEffect(() => {
    if (editing || type !== "TK" || !projectId) return;
    let live = true;
    listProjectBillStages(Number(projectId))
      .then((result) => {
        if (live) setStages(result.items);
      })
      .catch((e: ApiFailure) => {
        if (live) setError(e);
      });
    return () => {
      live = false;
    };
  }, [editing, type, projectId]);
  const change = (key: keyof typeof values, value: string) =>
    setValues((old) => ({ ...old, [key]: value }));
  const identity = () => {
    if (!projectId)
      throw new ApiFailure("프로젝트를 선택하세요.", 400, null, "project_id");
    if (type === "TK" && !stageId)
      throw new ApiFailure("미청구 단계를 선택하세요.", 400, null, "stage_id");
    return {
      projectId: Number(projectId),
      ...(type === "TK" ? { stageId: Number(stageId) } : { month }),
    };
  };
  const inspect = async () => {
    setPending(true);
    setError(null);
    try {
      const result = await previewBilling(identity());
      setPreview(result);
      setValues((old) => ({
        ...old,
        sply_amt: old.sply_amt || String(result.sply_amt),
        vat_amt: old.vat_amt || String(result.suggested_vat_amt),
        rcpt_plan_dt: old.rcpt_plan_dt || result.rcpt_plan_dt || "",
      }));
    } catch (e) {
      setError(e as ApiFailure);
    } finally {
      setPending(false);
    }
  };
  const money = (text: string, field: string) => {
    if (!/^-?\d+$/.test(text) || !Number.isSafeInteger(Number(text)))
      throw new ApiFailure("원 단위 정수를 입력하세요.", 400, null, field);
    return Number(text);
  };
  const save = async () => {
    setPending(true);
    setError(null);
    try {
      const body: BillingUpdate = {
        bill_dt: values.bill_dt || null,
        rcpt_plan_dt: values.rcpt_plan_dt || null,
        txinv_no: values.txinv_no || null,
        txinv_dt: values.txinv_dt || null,
      };
      if (values.sply_amt !== "")
        body.sply_amt = money(values.sply_amt, "sply_amt");
      if (values.vat_amt !== "")
        body.vat_amt = money(values.vat_amt, "vat_amt");
      if (remarkEdited) body.rmrk_desc = values.rmrk_desc;
      if (recalculate) body.recalculate = true;
      if (!editing || recalculate)
        body.details = Object.entries(overtime).map(([id, amount]) => ({
          asgn_id: Number(id),
          ovtm_amt: money(amount, "ovtm_amt"),
        }));
      let saved: BillingDetail;
      if (editing) saved = await updateBilling(detail.billing.bill_id!, body);
      else {
        identity();
        const {
          recalculate: _recalculate,
          rmrk_desc: _remark,
          ...create
        } = body;
        saved = await createBilling({
          ...create,
          project_id: Number(projectId),
          ...(type === "TK"
            ? { stage_id: Number(stageId) }
            : {
                month,
                additional,
                ...(order ? { bill_odr_no: money(order, "bill_odr_no") } : {}),
              }),
        });
      }
      onSaved(saved);
    } catch (e) {
      setError(e as ApiFailure);
    } finally {
      setPending(false);
    }
  };
  const lines = preview?.details ?? detail?.details ?? [];
  const difference =
    values.sply_amt !== "" &&
    lines.length > 0 &&
    Number(values.sply_amt) !==
      lines.reduce((sum, line) => sum + line.bill_amt, 0);
  const fieldError = (field: string) =>
    error?.field === field ? <span role="alert">{error.message}</span> : null;
  return (
    <Modal
      title={editing ? "청구 수정" : "청구 등록"}
      onCancel={() => {
        if (!pending) onClose();
      }}
    >
      <form
        data-testid="billing-editor"
        className={styles.form}
        onSubmit={(e) => {
          e.preventDefault();
          void save();
        }}
      >
        <ErrorBanner error={error} />
        {loadingOptions && <p role="status">프로젝트 불러오는 중</p>}
        <div className={tables.fields}>
          {editing ? (
            <label>
              프로젝트
              <input
                data-testid="billing-project-readonly"
                readOnly
                value={row?.project_name ?? ""}
              />
            </label>
          ) : (
            <label>
              프로젝트
              <select
                data-testid="billing-project"
                required
                value={projectId}
                onChange={(e) => {
                  setProjectId(e.target.value);
                  setStageId("");
                  setPreview(null);
                  setOvertime({});
                }}
              >
                <option value="">선택</option>
                {projects.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name} ({p.type})
                  </option>
                ))}
              </select>
              {fieldError("project_id")}
            </label>
          )}
          {type === "TK" ? (
            <label>
              TK 미청구 단계
              {editing ? (
                <input
                  data-testid="billing-stage-readonly"
                  readOnly
                  value={stageId}
                />
              ) : (
                <select
                  data-testid="billing-stage"
                  required
                  value={stageId}
                  onChange={(e) => {
                    setStageId(e.target.value);
                    setPreview(null);
                  }}
                >
                  <option value="">선택</option>
                  {stages
                    .filter((s) => !s.billed)
                    .map((s) => (
                      <option key={s.bill_stg_id} value={s.bill_stg_id}>
                        {s.stg_odr_no}차 {s.bill_stg_nm} ·{" "}
                        {s.bill_plan_dt || "예정일 미입력"} ·{" "}
                        {s.sply_amt.toLocaleString("ko-KR")}원
                      </option>
                    ))}
                </select>
              )}
              {fieldError("stage_id")}
            </label>
          ) : (
            <label>
              귀속월
              <input
                data-testid="billing-month"
                type="month"
                readOnly={editing}
                required
                value={month}
                onChange={(e) => {
                  setMonth(e.target.value);
                  setPreview(null);
                }}
              />
              {fieldError("month")}
            </label>
          )}
          {!editing && type !== "TK" && (
            <>
              <label>
                <input
                  data-testid="billing-additional"
                  type="checkbox"
                  checked={additional}
                  onChange={(e) => setAdditional(e.target.checked)}
                />
                추가 청구 명시
              </label>
              <label>
                차수
                <input
                  data-testid="billing-order"
                  type="number"
                  min="1"
                  max="2147483647"
                  required={additional}
                  value={order}
                  onChange={(e) => setOrder(e.target.value)}
                />
                {fieldError("bill_odr_no")}
              </label>
            </>
          )}
          {Object.entries({
            bill_dt: "청구일",
            sply_amt: "공급가액",
            vat_amt: "부가세",
            rcpt_plan_dt: "입금 예정일",
            txinv_no: "세금계산서 번호",
            txinv_dt: "작성일",
          }).map(([key, label]) => (
            <label key={key}>
              {label}
              <input
                data-testid={`billing-${key.replaceAll("_", "-")}`}
                aria-label={label}
                type={key.endsWith("_dt") ? "date" : "text"}
                inputMode={key.endsWith("_amt") ? "numeric" : undefined}
                maxLength={key === "txinv_no" ? 50 : undefined}
                value={values[key as keyof typeof values]}
                onChange={(e) =>
                  change(key as keyof typeof values, e.target.value)
                }
                aria-invalid={error?.field === key}
              />
              {fieldError(key)}
            </label>
          ))}
          <label>
            합계 (읽기 전용)
            <output data-testid="billing-total">
              {values.sply_amt === ""
                ? "자동 계산"
                : (
                    Number(values.sply_amt) + Number(values.vat_amt)
                  ).toLocaleString("ko-KR")}{" "}
              원
            </output>
          </label>
          {editing && (
            <label>
              비고
              <textarea
                data-testid="billing-remark"
                maxLength={500}
                value={values.rmrk_desc}
                onChange={(e) => {
                  change("rmrk_desc", e.target.value);
                  setRemarkEdited(true);
                }}
              />
              {fieldError("rmrk_desc")}
            </label>
          )}
        </div>
        {editing && (
          <label>
            <input
              data-testid="billing-recalculate"
              type="checkbox"
              checked={recalculate}
              onChange={(e) => setRecalculate(e.target.checked)}
            />
            원천 상세 다시 계산 (선택할 때만 상세 재생성)
          </label>
        )}
        {!editing && (
          <Button
            data-testid="billing-preview"
            type="button"
            pending={pending}
            onClick={() => void inspect()}
          >
            미리보기
          </Button>
        )}
        <p className={tables.help}>
          프로젝트·단계·귀속월은 생성 후 변경할 수 없습니다. 원천 예정일과 단계
          금액은 프로젝트에서 관리합니다.
        </p>
        {difference && (
          <p className={styles.warning}>
            공급가액과 상세 합계가 다릅니다. 수동 금액은 보존되며 확인 후 확정할
            수 있습니다.
          </p>
        )}
        {preview?.warnings.map((warning, index) => (
          <p key={index} className={styles.warning}>
            {warning}
          </p>
        ))}
        {!!preview?.missing_price_assignments.length && (
          <p role="alert">단가 없는 인력이 있어 자동 생성할 수 없습니다.</p>
        )}
        {lines.length > 0 && (
          <div className={tables.tableScroll}>
            <table className={tables.table}>
              <thead>
                <tr>
                  <th>계약상 인력</th>
                  <th>MM</th>
                  <th>단가</th>
                  <th>초과근무금액</th>
                  <th>공급가</th>
                </tr>
              </thead>
              <tbody>
                {lines.map((line) => (
                  <tr
                    key={line.asgn_id}
                    data-testid={`billing-preview-row-${line.asgn_id}`}
                  >
                    <td>{line.display_name}</td>
                    <td>{line.mm_qty ?? "근무일수 미입력"}</td>
                    <td>{line.untprc_amt?.toLocaleString("ko-KR")}</td>
                    <td>
                      <input
                        data-testid={`billing-overtime-${line.asgn_id}`}
                        aria-label={`${line.display_name} 초과근무금액`}
                        disabled={editing && !recalculate}
                        inputMode="numeric"
                        value={overtime[line.asgn_id] ?? line.ovtm_amt}
                        onChange={(e) =>
                          setOvertime((old) => ({
                            ...old,
                            [line.asgn_id]: e.target.value,
                          }))
                        }
                      />
                    </td>
                    <td>{line.bill_amt.toLocaleString("ko-KR")}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className={tables.actions}>
          {type === "DP" && onAttendance && (
            <Button
              data-testid="billing-attendance"
              type="button"
              onClick={() => onAttendance(Number(projectId), month)}
            >
              파견 근태
            </Button>
          )}
          <Button
            data-testid="billing-editor-cancel"
            type="button"
            disabled={pending}
            onClick={onClose}
          >
            취소
          </Button>
          <Button
            data-testid="billing-editor-save"
            type="submit"
            variant="primary"
            pending={pending}
            disabled={!!preview?.missing_price_assignments.length}
          >
            저장
          </Button>
        </div>
      </form>
    </Modal>
  );
}
