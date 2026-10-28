import { useCallback, useEffect, useRef, useState } from "react";
import { Button, Modal, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { listProjectOptions } from "../projects/client";
import { listProjectBillStages } from "../billstages/client";
import { seoulMonth } from "../shared/PeriodBar";
import { BillingLines } from "./BillingLines";
import {
  createBilling,
  previewBilling,
  updateBilling,
  type BillingDetail,
  type BillingPreview,
  type BillingCreate,
  type BillingUpdate,
} from "./client";
import tables from "../developers/Developers.module.css";
import styles from "./Billings.module.css";

export interface BillingEditorProps {
  existing?: BillingDetail;
  initial?: { projectId?: number; stageId?: number; month?: string };
  onClose: () => void;
  onSaved: (detail: BillingDetail) => Promise<unknown>;
  onDirtyChange?: (dirty: boolean) => void;
  onAttendance?: (projectId: number, month: string) => void;
}
export function BillingEditor({
  existing,
  initial,
  onClose,
  onSaved,
  onDirtyChange,
  onAttendance,
}: BillingEditorProps) {
  const original = existing?.billing;
  const [projectId, setProject] = useState(
    original?.prj_id ?? initial?.projectId ?? 0,
  );
  const [stageId, setStage] = useState(
    original?.bill_stg_id ?? initial?.stageId ?? 0,
  );
  const [month, setMonth] = useState(
    original?.atrb_ym ?? initial?.month ?? seoulMonth(),
  );
  const [additional, setAdditional] = useState(false),
    [ordinal, setOrdinal] = useState("");
  const [supply, setSupply] = useState(
    original ? String(original.sply_amt) : "",
  );
  const [vat, setVat] = useState(original ? String(original.vat_amt) : "");
  const [billDate, setBillDate] = useState(original?.bill_dt ?? "");
  const [due, setDue] = useState(original?.rcpt_plan_dt ?? "");
  const [taxNo, setTaxNo] = useState(original?.txinv_no ?? "");
  const [taxDate, setTaxDate] = useState(original?.txinv_dt ?? "");
  const [recalculate, setRecalculate] = useState(false),
    [remark, setRemark] = useState<string | null>(null);
  const [preview, setPreview] = useState<BillingPreview | null>(null);
  const [previewing, setPreviewing] = useState(false),
    [previewError, setPreviewError] = useState<ApiFailure | null>(null);
  const [overtime, setOvertime] = useState<Record<number, string>>({});
  const [dirty, setDirty] = useState(false);
  const projects = useAdminRead(listProjectOptions);
  const stages = useAdminRead(
    useCallback(
      () =>
        projectId ? listProjectBillStages(projectId) : Promise.resolve(null),
      [projectId],
    ),
  );
  const project = projects.data?.find((p) => p.id === projectId);
  const type = original?.contract_type ?? project?.type;
  const serial = useRef(0);
  const committed = useRef<BillingDetail | null>(null);
  const writer = useAdminWrite(async () => {
    if (committed.current) await onSaved(committed.current);
  });
  useEffect(() => {
    onDirtyChange?.(dirty);
  }, [dirty, onDirtyChange]);
  useEffect(() => () => onDirtyChange?.(false), [onDirtyChange]);
  const close = () => {
    if (!dirty || window.confirm("입력한 내용을 버릴까요?")) onClose();
  };
  const inspect = async () => {
    const request = ++serial.current;
    setPreviewing(true);
    setPreviewError(null);
    try {
      const result = await previewBilling(
        projectId,
        type === "TK" ? { stageId } : { month },
      );
      if (serial.current !== request) return;
      setPreview(result);
      if (!supply || recalculate) setSupply(String(result.sply_amt));
      if (!vat || recalculate) setVat(String(result.suggested_vat_amt));
      if (!due) setDue(result.rcpt_plan_dt ?? "");
    } catch (e) {
      if (serial.current === request) setPreviewError(e as ApiFailure);
    } finally {
      if (serial.current === request) setPreviewing(false);
    }
  };
  const lines = preview?.details ?? existing?.details ?? [];
  const missing = (preview?.missing_price_assignments.length ?? 0) > 0;
  const invalidMoney =
    (supply !== "" && !/^\d+$/.test(supply)) ||
    (vat !== "" && !/^\d+$/.test(vat));
  const resetPreview = () => {
    serial.current++;
    setPreview(null);
    setOvertime({});
    setSupply("");
    setVat("");
  };
  return (
    <Modal title={original ? "청구 수정" : "청구 등록"} onCancel={close}>
      <form
        data-testid="billing-editor"
        className={tables.form}
        onChange={() => setDirty(true)}
        onSubmit={(event) => {
          event.preventDefault();
          if (invalidMoney || !projectId || (type === "TK" ? !stageId : !month))
            return;
          const fields: BillingUpdate = {
            bill_dt: billDate || null,
            ...(supply === "" ? {} : { sply_amt: Number(supply) }),
            ...(vat === "" ? {} : { vat_amt: Number(vat) }),
            rcpt_plan_dt: due || null,
            txinv_no: taxNo || null,
            txinv_dt: taxDate || null,
          };
          const details = lines
            .filter((line) => line.asgn_id !== null)
            .map((line) => ({
              asgn_id: line.asgn_id!,
              ovtm_amt: Number(overtime[line.asgn_id!] ?? line.ovtm_amt),
            }));
          let saved: BillingDetail;
          void writer.save(async () => {
            if (original)
              saved = await updateBilling(original.bill_id!, {
                ...fields,
                ...(remark === null ? {} : { rmrk_desc: remark }),
                recalculate,
                ...(recalculate ? { details } : {}),
              });
            else {
              const payload: BillingCreate = {
                ...fields,
                project_id: projectId,
                ...(type === "TK" ? { stage_id: stageId } : { month }),
                additional,
                ...(additional ? { bill_odr_no: Number(ordinal) } : {}),
                details,
              };
              saved = await createBilling(payload);
            }
            committed.current = saved!;
            setDirty(false);
          }, onClose);
        }}
      >
        <ErrorBanner
          error={projects.error}
          onRetry={() => void projects.reload().catch(() => {})}
        />
        <ErrorBanner
          error={stages.error}
          onRetry={() => void stages.reload().catch(() => {})}
        />
        <div className={tables.fields}>
          <label>
            프로젝트
            <select
              aria-label="프로젝트"
              data-testid="billing-project"
              value={projectId}
              required
              disabled={!!original || projects.loading || writer.pending}
              onChange={(e) => {
                setProject(Number(e.target.value));
                setStage(0);
                resetPreview();
              }}
            >
              <option value={0}>선택하세요</option>
              {projects.data
                ?.filter((p) => p.type !== "IN")
                .map((p) => (
                  <option
                    key={p.id}
                    value={p.id}
                    data-testid={`billing-project-option-${p.id}`}
                  >
                    {p.name}
                  </option>
                ))}
            </select>
          </label>
          {type === "TK" ? (
            <label>
              TK 미청구 단계
              <select
                aria-label="TK 미청구 단계"
                data-testid="billing-stage"
                value={stageId}
                disabled={!!original || stages.loading}
                required
                onChange={(e) => {
                  setStage(Number(e.target.value));
                  resetPreview();
                }}
              >
                <option value={0}>선택하세요</option>
                {stages.data?.items
                  .filter((s) => !s.billed || s.bill_stg_id === stageId)
                  .map((s) => (
                    <option
                      key={s.bill_stg_id}
                      value={s.bill_stg_id}
                      data-testid={`billing-stage-option-${s.bill_stg_id}`}
                    >
                      {s.stg_odr_no}차 · {s.bill_stg_nm} ·{" "}
                      {s.sply_amt.toLocaleString()}원
                    </option>
                  ))}
              </select>
            </label>
          ) : (
            <label>
              귀속월
              <input
                aria-label="귀속월"
                data-testid="billing-month"
                type="month"
                required
                disabled={!!original}
                value={month}
                onChange={(e) => {
                  setMonth(e.target.value);
                  resetPreview();
                }}
              />
            </label>
          )}
          {!original && type !== "TK" && (
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
              {additional && (
                <label>
                  차수
                  <input
                    data-testid="billing-ordinal"
                    type="number"
                    min="2"
                    step="1"
                    required
                    value={ordinal}
                    onChange={(e) => setOrdinal(e.target.value)}
                  />
                </label>
              )}
            </>
          )}
          {original && (
            <label>
              <input
                data-testid="billing-recalculate"
                type="checkbox"
                checked={recalculate}
                onChange={(e) => setRecalculate(e.target.checked)}
              />
              원천 상세 다시 계산
            </label>
          )}
          <label>
            공급가액
            <input
              data-testid="billing-supply"
              type="number"
              min="0"
              step="1"
              value={supply}
              onChange={(e) => setSupply(e.target.value)}
            />
          </label>
          <label>
            부가세
            <input
              data-testid="billing-vat"
              type="number"
              min="0"
              step="1"
              value={vat}
              onChange={(e) => setVat(e.target.value)}
            />
          </label>
          <label>
            합계
            <input
              data-testid="billing-total"
              readOnly
              value={
                supply !== "" && vat !== ""
                  ? (Number(supply) + Number(vat)).toLocaleString()
                  : "자동 계산"
              }
            />
          </label>
          <label>
            청구일
            <input
              data-testid="billing-date"
              type="date"
              value={billDate}
              onChange={(e) => setBillDate(e.target.value)}
            />
          </label>
          <label>
            세금계산서 번호
            <input
              data-testid="billing-tax-number"
              maxLength={50}
              value={taxNo}
              onChange={(e) => setTaxNo(e.target.value)}
            />
          </label>
          <label>
            작성일
            <input
              data-testid="billing-tax-date"
              type="date"
              value={taxDate}
              onChange={(e) => setTaxDate(e.target.value)}
            />
          </label>
          <label>
            입금 예정일
            <input
              data-testid="billing-due"
              type="date"
              value={due}
              onChange={(e) => setDue(e.target.value)}
            />
          </label>
          {original && (
            <label>
              비고
              <input
                data-testid="billing-remark"
                maxLength={500}
                value={remark ?? ""}
                onChange={(e) => setRemark(e.target.value)}
              />
            </label>
          )}
        </div>
        <p className={tables.help}>
          합계는 서버가 계산합니다. 세금계산서는 발행하지 않고 정보만
          기록합니다.
        </p>
        {invalidMoney && (
          <p role="alert">금액은 0 이상의 원 단위 정수로 입력하세요.</p>
        )}
        <Button
          data-testid="billing-preview"
          type="button"
          disabled={!projectId || (type === "TK" ? !stageId : !month)}
          pending={previewing}
          onClick={() => void inspect()}
        >
          미리보기
        </Button>
        {previewing && <Skeleton label="원천 상세 불러오는 중" />}
        <ErrorBanner error={previewError} />
        {preview?.warnings.map((warning, i) => (
          <p
            className={styles.warning}
            key={i}
            data-testid={`billing-preview-warning-${i}`}
          >
            {warning}
          </p>
        ))}
        {missing && (
          <p className={styles.warning}>
            단가 없는 인력이 있어 자동 생성할 수 없습니다.
          </p>
        )}
        {lines.length > 0 && <BillingLines lines={lines} />}
        {type === "DP" &&
          lines
            .filter((line) => line.asgn_id !== null)
            .map((line) => (
              <label
                key={line.asgn_id}
                data-testid={`billing-overtime-label-${line.asgn_id}`}
              >
                {line.display_name} 초과근무금액
                <input
                  data-testid={`billing-overtime-${line.asgn_id}`}
                  type="number"
                  min="0"
                  step="1"
                  disabled={!!original && !recalculate}
                  value={overtime[line.asgn_id!] ?? line.ovtm_amt}
                  onChange={(e) =>
                    setOvertime((v) => ({
                      ...v,
                      [line.asgn_id!]: e.target.value,
                    }))
                  }
                />
              </label>
            ))}
        {supply !== "" &&
          lines.length > 0 &&
          Number(supply) !==
            lines.reduce((sum, line) => sum + line.bill_amt, 0) && (
            <p className={styles.warning}>
              상세 합과 공급가액이 다릅니다. 확인 후 확정할 수 있습니다.
            </p>
          )}
        <ErrorBanner error={writer.error} />
        {writer.error?.field && (
          <p role="alert">입력 항목: {writer.error.field}</p>
        )}
        <div className={tables.actions}>
          {type === "DP" && onAttendance && (
            <Button
              type="button"
              data-testid="billing-attendance"
              onClick={() => onAttendance(projectId, month)}
            >
              파견 근태
            </Button>
          )}
          <Button
            type="button"
            data-testid="billing-editor-close"
            onClick={close}
          >
            취소
          </Button>
          {writer.uncertain ? (
            <Button
              data-testid="billing-editor-recover"
              type="button"
              onClick={() => void writer.recover()}
            >
              현재 상태 확인
            </Button>
          ) : (
            <Button
              data-testid="billing-editor-save"
              variant="primary"
              type="submit"
              pending={writer.pending}
              disabled={
                missing ||
                invalidMoney ||
                !projectId ||
                (additional && !ordinal)
              }
            >
              저장
            </Button>
          )}
        </div>
      </form>
    </Modal>
  );
}
