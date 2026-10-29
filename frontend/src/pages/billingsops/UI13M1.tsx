import { useCallback, useEffect, useState, type ReactNode } from "react";
import { Button, Modal, Badge } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { failure } from "../admin/client";
import { listProjectOptions } from "../projects/client";
import { listProjectBillStages } from "../billstages/client";
import {
  previewBilling,
  saveBilling,
  type BillingDetail,
  type BillingPreview,
} from "./client";
import styles from "../developers/Developers.module.css";

export function UI13M1({
  existing,
  projectId,
  month: initialMonth,
  onClose,
  onSaved,
  reload,
  attendance,
}: {
  existing?: BillingDetail;
  projectId?: number;
  month?: string;
  onClose: () => void;
  onSaved: () => void;
  reload: () => Promise<unknown>;
  attendance?: (projectId: number, month: string) => ReactNode;
}) {
  const row = existing?.billing;
  const [project, setProject] = useState(row?.prj_id ?? projectId ?? 0);
  const [month, setMonth] = useState(row?.atrb_ym ?? initialMonth ?? "");
  const [stage, setStage] = useState(row?.bill_stg_id ?? 0);
  const [additional, setAdditional] = useState(false),
    [ordinal, setOrdinal] = useState("2");
  const [preview, setPreview] = useState<BillingPreview | null>(null);
  const [local, setLocal] = useState<ReturnType<typeof failure> | null>(null);
  const [previewing, setPreviewing] = useState(false),
    [showAttendance, setShowAttendance] = useState(false);
  const [draft, setDraft] = useState({
    sply_amt: String(row?.sply_amt ?? ""),
    vat_amt: String(row?.vat_amt ?? ""),
    bill_dt: row?.bill_dt ?? "",
    txinv_no: row?.txinv_no ?? "",
    txinv_dt: row?.txinv_dt ?? "",
    rcpt_plan_dt: row?.rcpt_plan_dt ?? "",
  });
  const projects = useAdminRead(listProjectOptions);
  const selected = projects.data?.find((p) => p.id === project);
  const type = row?.contract_type ?? selected?.type;
  const stages = useAdminRead(
    useCallback(
      () =>
        type === "TK" && project
          ? listProjectBillStages(project)
          : Promise.resolve(null),
      [type, project],
    ),
  );
  const write = useAdminWrite(reload);
  const [dirty, setDirty] = useState(false),
    [leaving, setLeaving] = useState(false);
  const close = () => {
    if (dirty) setLeaving(true);
    else onClose();
  };
  useEffect(() => {
    setPreview(null);
  }, [project, month, stage]);
  const change = (key: keyof typeof draft, value: string) => {
    setDirty(true);
    setLocal(null);
    setDraft((d) => ({ ...d, [key]: value }));
  };
  const readPreview = async () => {
    setLocal(null);
    setPreviewing(true);
    try {
      const result = await previewBilling(
        project,
        month,
        type === "TK" ? stage : undefined,
      );
      setPreview(result);
      setDraft((d) => ({
        ...d,
        sply_amt: String(result.sply_amt),
        vat_amt: String(result.suggested_vat_amt),
        rcpt_plan_dt: result.rcpt_plan_dt ?? d.rcpt_plan_dt,
      }));
    } catch (e) {
      setLocal(failure(e));
    } finally {
      setPreviewing(false);
    }
  };
  return (
    <Modal title={row ? "청구 수정" : "청구 등록"} onCancel={close}>
      <form
        data-testid="billing-editor-dialog"
        className={styles.form}
        onSubmit={(e) => {
          e.preventDefault();
          const supply = Number(draft.sply_amt),
            vat = Number(draft.vat_amt);
          if (
            !Number.isSafeInteger(supply) ||
            !Number.isSafeInteger(vat) ||
            !draft.sply_amt ||
            !draft.vat_amt ||
            !project ||
            (!row && (!preview || preview.missing_price_assignments.length))
          ) {
            setLocal(
              failure(new Error("필수 입력과 미리보기·단가를 확인하세요.")),
            );
            return;
          }
          const editable = {
            sply_amt: supply,
            vat_amt: vat,
            bill_dt: draft.bill_dt || null,
            txinv_no: draft.txinv_no || null,
            txinv_dt: draft.txinv_dt || null,
            rcpt_plan_dt: draft.rcpt_plan_dt || null,
          };
          const input = row
            ? editable
            : {
                ...editable,
                project_id: project,
                ...(type === "TK"
                  ? { stage_id: stage }
                  : {
                      month,
                      additional,
                      ...(additional ? { bill_odr_no: Number(ordinal) } : {}),
                    }),
              };
          void write.save(
            () => saveBilling(row?.bill_id ?? null, input),
            onSaved,
          );
        }}
      >
        {row ? (
          <p>
            프로젝트: {row.project_name} · 단계: {row.bill_stg_id ?? "–"} ·
            귀속월: {row.atrb_ym ?? "–"} (변경 불가)
          </p>
        ) : (
          <>
            <label>
              프로젝트
              <select
                data-testid="billing-editor-project"
                required
                value={project || ""}
                onChange={(e) => {
                  setDirty(true);
                  setProject(Number(e.target.value));
                  setStage(0);
                }}
              >
                <option value="">선택</option>
                {projects.data
                  ?.filter((p) => p.type !== "IN")
                  .map((p) => (
                    <option
                      key={p.id}
                      value={p.id}
                      data-testid={`billing-editor-project-option-${p.id}`}
                    >
                      {p.no} · {p.name}
                    </option>
                  ))}
              </select>
            </label>
            {type === "TK" ? (
              <label>
                TK 미청구 단계
                <select
                  data-testid="billing-editor-stage"
                  value={stage || ""}
                  onChange={(e) => {
                    setDirty(true);
                    setStage(Number(e.target.value));
                  }}
                >
                  <option value="">선택</option>
                  {stages.data?.items
                    .filter((s) => !s.billed)
                    .map((s) => (
                      <option
                        key={s.bill_stg_id}
                        value={s.bill_stg_id}
                        data-testid={`billing-editor-stage-option-${s.bill_stg_id}`}
                      >
                        {s.bill_stg_nm} · {s.bill_plan_dt}
                      </option>
                    ))}
                </select>
              </label>
            ) : (
              <>
                <label>
                  귀속월
                  <input
                    data-testid="billing-editor-month"
                    type="month"
                    required
                    value={month}
                    onChange={(e) => {
                      setDirty(true);
                      setMonth(e.target.value);
                    }}
                  />
                </label>
                <label>
                  <input
                    data-testid="billing-editor-additional"
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
                      data-testid="billing-editor-ordinal"
                      type="number"
                      min={2}
                      required
                      value={ordinal}
                      onChange={(e) => setOrdinal(e.target.value)}
                    />
                  </label>
                )}
              </>
            )}
            <Button
              data-testid="billing-editor-preview"
              type="button"
              pending={previewing}
              onClick={() => void readPreview()}
              disabled={!project || (type === "TK" ? !stage : !month)}
            >
              미리보기
            </Button>
          </>
        )}
        {(
          [
            ["sply_amt", "공급가액"],
            ["vat_amt", "부가세"],
            ["bill_dt", "청구일"],
            ["txinv_no", "세금계산서 번호"],
            ["txinv_dt", "세금계산서 작성일"],
            ["rcpt_plan_dt", "입금 예정일"],
          ] as const
        ).map(([key, label]) => (
          <label
            key={key}
            data-testid={`billing-editor-field-${key.replaceAll("_", "-")}`}
          >
            {label}
            <input
              data-testid={`billing-editor-${key.replaceAll("_", "-")}`}
              type={key.endsWith("_dt") ? "date" : "text"}
              value={draft[key]}
              onChange={(e) => change(key, e.target.value)}
            />
          </label>
        ))}
        <output>
          합계:{" "}
          {Number.isFinite(Number(draft.sply_amt) + Number(draft.vat_amt))
            ? (Number(draft.sply_amt) + Number(draft.vat_amt)).toLocaleString(
                "ko-KR",
              )
            : "–"}
          원
        </output>
        {(preview?.warnings ?? existing?.warnings ?? []).map((text, i) => (
          <Badge tone="warning" key={i}>
            {text}
          </Badge>
        ))}
        {preview && preview.sply_amt !== Number(draft.sply_amt) && (
          <Badge tone="warning">
            원천 상세 합과 공급가액이 다릅니다. 확인 후 확정할 수 있습니다.
          </Badge>
        )}
        {(preview?.details ?? existing?.details ?? []).map((line) => (
          <p
            key={line.asgn_id}
            data-testid={`billing-editor-line-${line.asgn_id}`}
          >
            {line.display_name} · MM {line.mm_qty ?? "–"} · 단가{" "}
            {line.untprc_amt?.toLocaleString("ko-KR") ?? "미입력"} ·{" "}
            {line.bill_amt.toLocaleString("ko-KR")}원
          </p>
        ))}
        {type === "DP" && attendance && (
          <>
            <Button
              data-testid="billing-editor-attendance"
              type="button"
              onClick={() => setShowAttendance(true)}
            >
              파견 근태
            </Button>
            {showAttendance && attendance(project, month)}
          </>
        )}
        <ErrorBanner
          error={projects.error ?? stages.error ?? local ?? write.error}
          onRetry={() => void write.recover()}
        />
        {(local ?? write.error)?.field && (
          <p role="alert">
            {(local ?? write.error)?.field}: {(local ?? write.error)?.message}
          </p>
        )}
        <div className={styles.actions}>
          <Button
            data-testid="billing-editor-close"
            type="button"
            onClick={close}
            disabled={write.pending}
          >
            취소
          </Button>
          <Button
            data-testid="billing-editor-save"
            type="submit"
            variant="primary"
            pending={write.pending}
            disabled={write.uncertain || projects.loading || (!row && !preview)}
          >
            저장
          </Button>
        </div>
      </form>
      {leaving && (
        <Modal
          title="작성 중인 내용을 버릴까요?"
          confirmation
          onCancel={() => setLeaving(false)}
        >
          <Button
            data-testid="billing-editor-keep"
            onClick={() => setLeaving(false)}
          >
            계속 작성
          </Button>
          <Button data-testid="billing-editor-discard" onClick={onClose}>
            나가기
          </Button>
        </Modal>
      )}
    </Modal>
  );
}
