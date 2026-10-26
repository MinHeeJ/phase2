import { useCallback, useEffect, useRef, useState } from "react";
import {
  Badge,
  Button,
  Modal,
  Skeleton,
  LockedBanner,
  SuccessToast,
} from "../../shared/Controls";
import {
  TextInput,
  SelectInput,
  TextareaInput,
  MoneyInput,
} from "../../shared/Inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import * as client from "./client";
import type {
  ContractChangeInput,
  ContractChangeRow,
  ContractChangeProject,
} from "./client";
import styles from "./ProjectContractChanges.module.css";

const moneyText = (value: number | null | undefined) =>
  value == null ? "–" : `${value.toLocaleString("ko-KR")}원`;
const reallocation = "현재 계약금액을 바꿨습니다. 배분표를 다시 맞춰 주세요.";

// SCR-UI-01: selected project's 계약 변경 이력 tab; embedded by the project drawer.
export function ProjectContractChangePanel({
  project,
  onChanged,
}: {
  project: ContractChangeProject;
  onChanged: () => Promise<unknown>;
}) {
  const read = useCallback(
    () => client.listProjectContractChanges(project.prj_id),
    [project.prj_id],
  );
  const state = useAdminRead(read);
  const codes = useCodes("CONTRACT_CHANGE");
  const mobile = useDeveloperReadOnly();
  const readonly = mobile || project.use_yn === "N";
  const [editing, setEditing] = useState<ContractChangeRow | "new" | null>(
    null,
  );
  const [action, setAction] = useState<{
    row: ContractChangeRow;
    kind: "confirm" | "delete";
  } | null>(null);
  const [message, setMessage] = useState("");
  useEffect(() => {
    setEditing(null);
    setAction(null);
    setMessage("");
  }, [project.prj_id, readonly]);
  const refresh = async () => {
    await state.reload();
    await onChanged();
  };
  const saved = (row: ContractChangeRow) => {
    setEditing(null);
    setAction(null);
    setMessage(
      row.cntrct_chg_stts_cd === "CONFIRM" &&
        project.cntrct_ty_cd === "TK" &&
        row.cntrct_chg_ty_cd !== "FREE"
        ? reallocation
        : "저장했습니다.",
    );
  };
  const name = (type: string) =>
    codes.codes.find((c) => c.code === type)?.name ?? type;
  return (
    <section
      role="tabpanel"
      aria-label="계약 변경 이력"
      data-testid="contract-change-panel"
      data-screen-id="SCR-UI-01"
    >
      <div className={styles.toolbar}>
        <h3>계약 변경 이력</h3>
        {!readonly && (
          <Can perm="UI-01.update">
            <Button
              data-testid="contract-change-add"
              onClick={() => setEditing("new")}
            >
              + 계약 변경
            </Button>
          </Can>
        )}
      </div>
      {project.use_yn === "N" && (
        <LockedBanner>사용 안 함으로 바꾼 프로젝트입니다.</LockedBanner>
      )}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      <ErrorBanner
        error={state.error}
        onRetry={() => void state.reload().catch(() => {})}
      />
      <ErrorBanner error={codes.error} onRetry={codes.retry} />
      {state.loading ? (
        <Skeleton />
      ) : (
        !state.error &&
        state.data && (
          <>
            {project.cntrct_ty_cd === "TK" &&
              state.data.items.some((r) => r.realoc_need_yn === "Y") && (
                <div className={styles.warning}>
                  계약 변경이 반영되었습니다. 배분표를 다시 맞춰 주세요.
                </div>
              )}
            <div className={styles.tableScroll}>
              <table className={styles.table}>
                <thead>
                  <tr>
                    {[
                      "변경일",
                      "내용",
                      "변경 후 금액",
                      "사유",
                      "상태",
                      "확정 정보",
                      "작업",
                    ].map((label) => (
                      <th
                        key={label}
                        data-testid={`contract-change-header-${["변경일", "내용", "변경 후 금액", "사유", "상태", "확정 정보", "작업"].indexOf(label)}`}
                      >
                        {label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  <tr data-testid="contract-change-initial">
                    <td>{state.data.cntrct_bgn_dt}</td>
                    <td>최초 계약</td>
                    <td>{moneyText(state.data.init_cntrct_amt)}</td>
                    <td>–</td>
                    <td>–</td>
                    <td>–</td>
                    <td>–</td>
                  </tr>
                  {state.data.items.map((row) => (
                    <tr
                      key={row.chgId}
                      data-testid={`contract-change-row-${row.chgId}`}
                    >
                      <td>{row.chg_dt}</td>
                      <td>
                        {name(row.cntrct_chg_ty_cd)}
                        {row.cntrct_chg_ty_cd === "TERM" && (
                          <small>
                            {row.bfr_end_dt} → {row.aft_end_dt}
                          </small>
                        )}
                        {row.cntrct_chg_ty_cd === "FREE" && (
                          <small>{row.free_mm_qty} MM</small>
                        )}
                        {row.incdec_mm_qty != null && (
                          <small>증감 {row.incdec_mm_qty} MM</small>
                        )}
                      </td>
                      <td>{moneyText(row.aft_cntrct_amt)}</td>
                      <td className={styles.reason}>{row.rsn_desc}</td>
                      <td>
                        <Badge
                          tone={
                            row.cntrct_chg_stts_cd === "PLAN"
                              ? "warning"
                              : "success"
                          }
                        >
                          {row.cntrct_chg_stts_cd === "PLAN" ? "예정" : "확정"}
                        </Badge>
                      </td>
                      <td>
                        {row.cnfrm_at ? (
                          <>
                            {row.cnfrm_at.replace("T", " ")}
                            <small>기본 사용자</small>
                          </>
                        ) : (
                          "–"
                        )}
                      </td>
                      <td>
                        {!readonly && row.cntrct_chg_stts_cd === "PLAN" && (
                          <div className={styles.actions}>
                            <Can perm="UI-01.confirm">
                              <Button
                                small
                                data-testid={`contract-change-confirm-${row.chgId}`}
                                onClick={() =>
                                  setAction({ row, kind: "confirm" })
                                }
                              >
                                확정
                              </Button>
                            </Can>
                            <Can perm="UI-01.update">
                              <Button
                                small
                                data-testid={`contract-change-edit-${row.chgId}`}
                                onClick={() => setEditing(row)}
                              >
                                수정
                              </Button>
                              <Button
                                small
                                data-testid={`contract-change-delete-${row.chgId}`}
                                onClick={() =>
                                  setAction({ row, kind: "delete" })
                                }
                              >
                                삭제
                              </Button>
                            </Can>
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {state.data.items.length === 0 && (
              <p className={styles.empty}>등록된 계약 변경이 없습니다.</p>
            )}
          </>
        )
      )}
      {!readonly && editing && (
        <ProjectContractChangeForm
          key={editing === "new" ? "new" : editing.chgId}
          project={project}
          initial={editing === "new" ? undefined : editing}
          reload={refresh}
          onCancel={() => setEditing(null)}
          onSaved={saved}
        />
      )}
      {!readonly && action && (
        <ContractChangeAction
          project={project}
          action={action}
          reload={refresh}
          onCancel={() => setAction(null)}
          onSaved={saved}
          onDeleted={() => {
            setAction(null);
            setMessage("삭제했습니다.");
          }}
        />
      )}
    </section>
  );
}

export function ProjectContractChangeForm({
  project,
  initial,
  reload,
  onCancel,
  onSaved,
}: {
  project: ContractChangeProject;
  initial?: ContractChangeRow;
  reload: () => Promise<unknown>;
  onCancel: () => void;
  onSaved: (row: ContractChangeRow) => void;
}) {
  const [draft, setDraft] = useState<ContractChangeInput>(() => ({
    cntrct_chg_ty_cd: initial?.cntrct_chg_ty_cd ?? "",
    cntrct_chg_stts_cd: "PLAN",
    chg_dt: initial?.chg_dt ?? "",
    rsn_desc: initial?.rsn_desc ?? "",
    ...(initial
      ? {
          incdec_mm_qty: initial.incdec_mm_qty,
          aft_end_dt: initial.aft_end_dt,
          free_mm_qty: initial.free_mm_qty,
          amount:
            initial.bfr_cntrct_amt != null && initial.aft_cntrct_amt != null
              ? Math.abs(initial.aft_cntrct_amt - initial.bfr_cntrct_amt)
              : undefined,
        }
      : {}),
  }));
  const [dirty, setDirty] = useState(false),
    [leaving, setLeaving] = useState(false);
  const [mmText, setMmText] = useState(
    initial?.incdec_mm_qty?.toString() ?? "",
  );
  const [freeText, setFreeText] = useState(
    initial?.free_mm_qty?.toString() ?? "",
  );
  const [localError, setLocalError] = useState<{
    field: string;
    message: string;
  } | null>(null);
  const codes = useCodes("CONTRACT_CHANGE");
  const write = useAdminWrite(reload);
  const form = useRef<HTMLFormElement>(null);
  const error = localError ?? write.error;
  useEffect(() => {
    if (error?.field)
      form.current
        ?.querySelector<HTMLElement>(
          `[name="${error.field.replace(/[^a-z_]/g, "")}"]`,
        )
        ?.focus();
  }, [error]);
  const change = (
    key: keyof ContractChangeInput,
    value: string | number | null,
  ) => {
    setDirty(true);
    setLocalError(null);
    write.clear();
    if (key === "cntrct_chg_ty_cd") {
      setMmText("");
      setFreeText("");
    }
    setDraft((d) => ({
      ...d,
      [key]: value,
      ...(key === "cntrct_chg_ty_cd"
        ? {
            amount: undefined,
            incdec_mm_qty: null,
            aft_end_dt: null,
            free_mm_qty: null,
          }
        : {}),
    }));
  };
  const numeric =
    draft.cntrct_chg_ty_cd === "ADD" || draft.cntrct_chg_ty_cd === "CUT";
  const blocked = write.pending || write.uncertain;
  const cancel = () => {
    if (!write.pending) {
      if (dirty) setLeaving(true);
      else onCancel();
    }
  };
  const fieldError = (key: string) =>
    error?.field === key ? error.message : null;
  const save = () => {
    let field = "";
    if (!draft.cntrct_chg_ty_cd) field = "cntrct_chg_ty_cd";
    else if (!draft.chg_dt) field = "chg_dt";
    else if (
      numeric &&
      (!draft.amount || !Number.isSafeInteger(draft.amount) || draft.amount < 1)
    )
      field = "amount";
    else if (
      draft.cntrct_chg_ty_cd === "TERM" &&
      (!draft.aft_end_dt || draft.aft_end_dt === project.cur_end_dt)
    )
      field = "aft_end_dt";
    else if (draft.cntrct_chg_ty_cd === "FREE" && draft.free_mm_qty == null)
      field = "free_mm_qty";
    else if (!draft.rsn_desc.trim() || draft.rsn_desc.trim().length > 500)
      field = "rsn_desc";
    const decimalValid = (value: string) =>
      /^-?\d+(\.\d{1,2})?$/.test(value) && Math.abs(Number(value)) < 1000000;
    if (
      numeric &&
      project.cntrct_ty_cd === "TK" &&
      mmText &&
      !decimalValid(mmText)
    )
      field = "incdec_mm_qty";
    if (draft.cntrct_chg_ty_cd === "FREE" && !decimalValid(freeText))
      field = "free_mm_qty";
    if (field) {
      setLocalError({
        field,
        message:
          field === "aft_end_dt"
            ? "현재 종료일과 다른 종료일을 입력하세요."
            : "필수 항목과 입력 범위를 확인하세요.",
      });
      return;
    }
    let result: ContractChangeRow;
    void write.save(
      async () => {
        const payload = {
          ...draft,
          incdec_mm_qty:
            project.cntrct_ty_cd === "TK" && mmText ? Number(mmText) : null,
          free_mm_qty: freeText ? Number(freeText) : null,
        };
        result = initial
          ? await client.updateProjectContractChange(
              project.prj_id,
              initial.chgId,
              payload,
            )
          : await client.createProjectContractChange(project.prj_id, payload);
      },
      () => onSaved(result),
    );
  };
  return (
    <Modal
      title={initial ? "계약 변경 수정" : "계약 변경 등록"}
      onCancel={cancel}
    >
      <form
        ref={form}
        noValidate
        className={styles.form}
        data-testid="contract-change-form"
        data-screen-id="SCR-UI-01-M5"
        onSubmit={(e) => {
          e.preventDefault();
          save();
        }}
      >
        <ErrorBanner
          error={write.error}
          pending={write.pending}
          onRetry={() => void write.recover()}
        />
        <ErrorBanner error={codes.error} onRetry={codes.retry} />
        <SelectInput
          name="cntrct_chg_ty_cd"
          label="종류"
          required
          value={draft.cntrct_chg_ty_cd}
          disabled={blocked || codes.loading || !!codes.error}
          error={fieldError("cntrct_chg_ty_cd")}
          options={codes.codes
            .filter((c) => project.cntrct_ty_cd !== "IN" || c.code === "TERM")
            .map((c) => ({ value: c.code, label: c.name }))}
          onValueChange={(v) => change("cntrct_chg_ty_cd", v)}
        />
        <div className={styles.dates}>
          <SelectInput
            name="cntrct_chg_stts_cd"
            label="상태"
            required
            value={draft.cntrct_chg_stts_cd}
            disabled={blocked}
            options={[
              { value: "PLAN", label: "예정" },
              { value: "CONFIRM", label: "확정" },
            ]}
            onValueChange={(v) => change("cntrct_chg_stts_cd", v)}
          />
          <TextInput
            name="chg_dt"
            label="변경일"
            type="date"
            required
            value={draft.chg_dt}
            disabled={blocked}
            error={fieldError("chg_dt")}
            onValueChange={(v) => change("chg_dt", v)}
          />
        </div>
        {numeric && (
          <MoneyInput
            name="amount"
            label="금액"
            required
            value={draft.amount ?? null}
            disabled={blocked}
            error={fieldError("amount")}
            onValueChange={(v) => change("amount", v)}
          />
        )}
        {numeric && project.cntrct_ty_cd === "TK" && (
          <TextInput
            name="incdec_mm_qty"
            label="증감 MM"
            value={mmText}
            disabled={blocked}
            error={fieldError("incdec_mm_qty")}
            onValueChange={(v) => {
              setMmText(v);
              change("incdec_mm_qty", v === "" ? null : Number(v));
            }}
          />
        )}
        {draft.cntrct_chg_ty_cd === "TERM" && (
          <TextInput
            name="aft_end_dt"
            label="변경 후 종료일"
            type="date"
            required
            value={draft.aft_end_dt ?? ""}
            disabled={blocked}
            error={fieldError("aft_end_dt")}
            onValueChange={(v) => change("aft_end_dt", v)}
          />
        )}
        {draft.cntrct_chg_ty_cd === "FREE" && (
          <TextInput
            name="free_mm_qty"
            label="무상지원 MM"
            required
            value={freeText}
            disabled={blocked}
            error={fieldError("free_mm_qty")}
            onValueChange={(v) => {
              setFreeText(v);
              change("free_mm_qty", v === "" ? null : Number(v));
            }}
          />
        )}
        <TextareaInput
          name="rsn_desc"
          label="사유"
          required
          maxLength={500}
          value={draft.rsn_desc}
          disabled={blocked}
          error={fieldError("rsn_desc")}
          onValueChange={(v) => change("rsn_desc", v)}
        />
        <div className={styles.actions}>
          <Button
            type="button"
            data-testid="contract-change-cancel"
            disabled={write.pending}
            onClick={cancel}
          >
            취소
          </Button>
          <Can
            perm={
              draft.cntrct_chg_stts_cd === "CONFIRM"
                ? "UI-01.confirm"
                : "UI-01.update"
            }
          >
            <Button
              type="submit"
              variant="primary"
              data-testid="contract-change-save"
              pending={write.pending}
              disabled={write.uncertain || codes.loading || !!codes.error}
            >
              저장
            </Button>
          </Can>
        </div>
      </form>
      {leaving && (
        <Modal
          title="작성 중인 내용을 버릴까요?"
          confirmation
          onCancel={() => setLeaving(false)}
        >
          <div data-testid="contract-change-discard-dialog">
            <Button
              data-testid="contract-change-keep"
              onClick={() => setLeaving(false)}
            >
              계속 작성
            </Button>
            <Button data-testid="contract-change-discard" onClick={onCancel}>
              나가기
            </Button>
          </div>
        </Modal>
      )}
    </Modal>
  );
}

function ContractChangeAction({
  project,
  action,
  reload,
  onCancel,
  onSaved,
  onDeleted,
}: {
  project: ContractChangeProject;
  action: { row: ContractChangeRow; kind: "confirm" | "delete" };
  reload: () => Promise<unknown>;
  onCancel: () => void;
  onSaved: (row: ContractChangeRow) => void;
  onDeleted: () => void;
}) {
  const write = useAdminWrite(reload);
  const confirming = action.kind === "confirm";
  const cancel = () => {
    if (!write.pending) onCancel();
  };
  return (
    <Modal
      title={
        confirming ? "이 계약 변경을 확정할까요?" : "이 계약 변경을 삭제할까요?"
      }
      confirmation
      onCancel={cancel}
    >
      <div
        data-testid="contract-change-action-dialog"
        data-screen-id={confirming ? "SCR-UI-01-M6" : undefined}
      >
        {confirming && (
          <p>현재 계약금액·종료일에 바로 반영되고 되돌릴 수 없습니다.</p>
        )}
        <ErrorBanner
          error={write.error}
          pending={write.pending}
          onRetry={() => void write.recover()}
        />
        <div className={styles.actions}>
          <Button
            data-testid="contract-change-action-cancel"
            disabled={write.pending}
            onClick={cancel}
          >
            취소
          </Button>
          <Button
            variant="primary"
            data-testid="contract-change-action-submit"
            pending={write.pending}
            disabled={write.uncertain}
            onClick={() => {
              let row: ContractChangeRow;
              void write.save(
                async () => {
                  if (confirming)
                    row = await client.updateProjectContractChangeConfirmation(
                      project.prj_id,
                      action.row.chgId,
                    );
                  else
                    await client.updateProjectContractChangeRemoval(
                      project.prj_id,
                      action.row.chgId,
                    );
                },
                () => (confirming ? onSaved(row) : onDeleted()),
              );
            }}
          >
            {confirming ? "확정" : "삭제"}
          </Button>
        </div>
      </div>
    </Modal>
  );
}
