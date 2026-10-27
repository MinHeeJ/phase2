import { useCallback, useState } from "react";
import { Button, Modal } from "../../shared/Controls";
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
import { getMonthClose } from "../shared/api";
import { DeveloperSelect } from "../developersops/DeveloperSelect";
import {
  changeNames,
  createAssignmentChange,
  createAssignmentFix,
  type AssignmentChange,
  type ChangeContext,
  type ChangeRequest,
  type EditableChangeType,
  type SaveResult,
  type FixResult,
} from "./client";
import styles from "./AssignmentChanges.module.css";

const nextDay = (date: string) => {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
};
const seoulToday = () =>
  new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Seoul" }).format(
    new Date(),
  );
type FormProps = ChangeContext & {
  reload: () => Promise<unknown>;
  onCancel: () => void;
  today?: string;
};

function Discard({
  onCancel,
  onDiscard,
}: {
  onCancel: () => void;
  onDiscard: () => void;
}) {
  return (
    <Modal title="작성 중인 내용을 버릴까요?" confirmation onCancel={onCancel}>
      <div data-testid="changes-discard-dialog" className={styles.actions}>
        <Button data-testid="changes-keep" onClick={onCancel}>
          계속 작성
        </Button>
        <Button data-testid="changes-discard" onClick={onDiscard}>
          나가기
        </Button>
      </div>
    </Modal>
  );
}
export function AssignmentChangeForm({
  assignment: a,
  contractType,
  readOnly = false,
  projectWritable = true,
  reload,
  onCancel,
  onSaved,
  today = seoulToday(),
}: FormProps & { onSaved: (result: SaveResult) => void }) {
  const [version] = useState(a.updated_at);
  const [type, setType] = useState<EditableChangeType>(
    a.asgn_stts_cd === "REPLACED" ? "RATE" : "EXTEND",
  );
  const [date, setDate] = useState(today),
    [end, setEnd] = useState(""),
    [start, setStart] = useState(a.bgn_dt);
  const [month, setMonth] = useState(""),
    [price, setPrice] = useState<number | null>(null);
  const [chance, setChance] = useState(a.ext_psblty_cd ?? ""),
    [kind, setKind] = useState(a.asgn_se_cd);
  const [person, setPerson] = useState(a.cntrct_dev_id),
    [reason, setReason] = useState(""),
    [memo, setMemo] = useState("");
  const [dirty, setDirty] = useState(false),
    [leaving, setLeaving] = useState(false);
  const [local, setLocal] = useState<{ field: string; message: string } | null>(
    null,
  );
  const reasons = useCodes("ASGN_CHG_RSN"),
    kinds = useCodes("ASSIGN_KIND"),
    chances = useCodes("EXT_POSSIBILITY");
  const boundary = useAdminRead(
    useCallback(() => getMonthClose("0001-01", "9999-12"), []),
  );
  const lastClosed = boundary.data
    ?.filter((r) => r.status === "CLOSED")
    .map((r) => r.month)
    .sort()
    .at(-1);
  const write = useAdminWrite(reload);
  const locked =
    readOnly ||
    !projectWritable ||
    ["CANCELLED", "ENDED"].includes(a.asgn_stts_cd);
  const allowed = (Object.keys(changeNames) as EditableChangeType[]).filter(
    (t) =>
      ["EXTEND", "EARLY_END", "RATE", "EXT_CHANCE", "PERIOD"].includes(t) &&
      (t !== "RATE" || ["MO", "DP"].includes(contractType)) &&
      (a.asgn_stts_cd !== "REPLACED" || t === "RATE"),
  );
  const application =
    type === "EXTEND"
      ? nextDay(a.end_dt)
      : type === "RATE"
        ? month === a.bgn_dt.slice(0, 7)
          ? a.bgn_dt
          : month
            ? `${month}-01`
            : ""
        : date;
  const retro = !!application && application < today;
  const closed =
    !!application && !!lastClosed && application.slice(0, 7) <= lastClosed;
  const required =
    retro ||
    closed ||
    type === "EARLY_END" ||
    (type === "PERIOD" && !!end && end !== a.end_dt);
  const error = local ?? write.error;
  const fieldError = (name: string) =>
    error?.field === name ? error.message : null;
  const touch = () => {
    setDirty(true);
    setLocal(null);
    write.clear();
  };
  const blocked =
    write.pending ||
    write.uncertain ||
    write.error?.code === "STALE_ASSIGNMENT" ||
    locked;
  const cancel = () => {
    if (!write.pending) {
      if (dirty) setLeaving(true);
      else onCancel();
    }
  };
  const fail = (field: string, message: string) => {
    setLocal({ field, message });
  };
  const save = () => {
    if (blocked) return;
    if (!allowed.includes(type))
      return fail("asgn_chg_ty_cd", "변경 종류를 확인하세요.");
    if (!application) return fail("aply_dt", "적용일을 입력하세요.");
    if (application < a.bgn_dt)
      return fail(
        "aply_dt",
        `투입일(${a.bgn_dt})보다 앞선 날짜는 넣을 수 없습니다.`,
      );
    if (required && !reason) return fail("rsn_cd", "사유를 골라 주세요.");
    const common = {
      updated_at: version,
      aply_dt: application,
      rsn_cd: reason || null,
      rsn_desc: memo || null,
    };
    let input: ChangeRequest;
    switch (type) {
      case "EXTEND":
        if (!end || end <= a.end_dt)
          return fail(
            "end_dt",
            `새 종료일은 현재 종료일(${a.end_dt})보다 늦어야 합니다.`,
          );
        input = { ...common, asgn_chg_ty_cd: type, end_dt: end };
        break;
      case "EARLY_END":
        if (date >= a.end_dt)
          return fail(
            "aply_dt",
            date === a.end_dt
              ? "현재 종료일과 같습니다."
              : "현재 종료일보다 앞선 날짜를 입력하세요.",
          );
        input = { ...common, asgn_chg_ty_cd: type };
        break;
      case "RATE": {
        if (
          !month ||
          month < a.bgn_dt.slice(0, 7) ||
          month > a.end_dt.slice(0, 7)
        )
          return fail("work_ym", "투입 기간 안의 달을 고르세요.");
        if (!price || !Number.isSafeInteger(price) || price < 1)
          return fail("bill_untprc_amt", "1원 이상 입력하세요.");
        const current = a.rates
          ?.filter((r) => r.aply_bgn_dt <= application)
          .sort((x, y) => y.aply_bgn_dt.localeCompare(x.aply_bgn_dt))[0];
        if (current?.bill_untprc_amt === price)
          return fail("bill_untprc_amt", "현재 값과 같습니다.");
        input = {
          ...common,
          asgn_chg_ty_cd: type,
          work_ym: month,
          bill_untprc_amt: price,
        };
        break;
      }
      case "EXT_CHANCE":
        if ((chance || null) === a.ext_psblty_cd)
          return fail("ext_psblty_cd", "현재 값과 같습니다.");
        input = {
          ...common,
          asgn_chg_ty_cd: type,
          ext_psblty_cd: chance || null,
        };
        break;
      case "PERIOD": {
        const finish = end || a.end_dt;
        if (start > finish)
          return fail("end_dt", "시작일은 종료일보다 늦을 수 없습니다.");
        if (
          start === a.bgn_dt &&
          finish === a.end_dt &&
          kind === a.asgn_se_cd &&
          (kind !== "SUB" || person === a.cntrct_dev_id)
        )
          return fail("asgn_se_cd", "바뀐 내용이 없습니다.");
        if (kind === "SUB" && person == null)
          return fail("cntrct_dev_id", "계약상 인력을 고르세요.");
        if (
          start !== a.bgn_dt &&
          boundary.data?.some(
            (r) =>
              r.status === "CLOSED" &&
              [start.slice(0, 7), a.bgn_dt.slice(0, 7)].includes(r.month),
          )
        )
          return fail("bgn_dt", "마감된 시작월은 바꿀 수 없습니다.");
        input = {
          ...common,
          asgn_chg_ty_cd: type,
          asgn_se_cd: kind,
          ...(start !== a.bgn_dt ? { bgn_dt: start } : {}),
          ...(end ? { end_dt: end } : {}),
          ...(kind === "SUB" ? { cntrct_dev_id: person } : {}),
        };
        break;
      }
    }
    let result: SaveResult;
    void write.save(
      async () => {
        result = await createAssignmentChange(a.asgn_id, input);
      },
      () => onSaved(result),
    );
  };
  return (
    <Modal title="변경 기록" onCancel={cancel}>
      <form
        data-testid="assignment-change-form"
        className={styles.form}
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          save();
        }}
        onChange={touch}
      >
        <p>
          변경 전 {a.dev_nm} · {a.prj_nm} · {a.bgn_dt} ~ {a.end_dt}
        </p>
        {a.warnings?.map((w, index) => (
          <div
            data-testid={`assignment-change-warning-${index}`}
            className={styles.warning}
            key={`${w.code}-${w.work_ym}`}
          >
            {w.message}
          </div>
        ))}
        {retro && <div className={styles.warning}>소급 입력입니다.</div>}
        {closed && (
          <div className={styles.warning}>
            마감월 숫자는 바뀌지 않고 첫 미마감월부터 반영합니다.
          </div>
        )}
        <ErrorBanner error={write.error} onRetry={() => void write.recover()} />
        <ErrorBanner
          error={boundary.error}
          onRetry={() => void boundary.reload().catch(() => {})}
        />
        <ErrorBanner error={reasons.error} onRetry={reasons.retry} />
        <ErrorBanner error={kinds.error} onRetry={kinds.retry} />
        <ErrorBanner error={chances.error} onRetry={chances.retry} />
        <SelectInput
          name="asgn_chg_ty_cd"
          label="변경 종류"
          value={type}
          required
          disabled={blocked}
          options={allowed.map((t) => ({ value: t, label: changeNames[t] }))}
          error={fieldError("asgn_chg_ty_cd")}
          onValueChange={(v) => {
            setType(v as EditableChangeType);
            setEnd("");
            setMonth("");
            setPrice(null);
          }}
        />
        {type === "EXTEND" && (
          <TextInput
            label="연장 시작일"
            name="aply_dt"
            type="date"
            readOnly
            value={application}
            onValueChange={() => {}}
          />
        )}
        {["EARLY_END", "EXT_CHANCE", "PERIOD"].includes(type) && (
          <TextInput
            label={type === "EARLY_END" ? "철수일" : "바꾼 날"}
            name="aply_dt"
            type="date"
            required
            disabled={blocked}
            value={date}
            onValueChange={setDate}
            error={fieldError("aply_dt")}
          />
        )}
        {(type === "EXTEND" || type === "PERIOD") && (
          <TextInput
            label={type === "EXTEND" ? "새 종료일" : "종료일"}
            name="end_dt"
            type="date"
            value={end}
            onValueChange={setEnd}
            disabled={blocked}
            error={fieldError("end_dt")}
          />
        )}
        {type === "RATE" && (
          <>
            <TextInput
              label="적용 시작월"
              name="work_ym"
              type="month"
              value={month}
              onValueChange={setMonth}
              disabled={blocked}
              error={fieldError("work_ym")}
            />
            <MoneyInput
              label="새 청구 단가"
              name="bill_untprc_amt"
              value={price}
              onValueChange={setPrice}
              disabled={blocked}
              error={fieldError("bill_untprc_amt")}
            />
          </>
        )}
        {type === "EXT_CHANCE" && (
          <SelectInput
            label="연장 가능성"
            name="ext_psblty_cd"
            value={chance}
            onValueChange={setChance}
            disabled={blocked}
            options={[
              { value: "", label: "미정" },
              ...chances.codes.map((c) => ({ value: c.code, label: c.name })),
            ]}
            error={fieldError("ext_psblty_cd")}
          />
        )}
        {type === "PERIOD" && (
          <>
            <div className={styles.warning}>
              최초 입력 오류를 바로잡을 때만 사용하세요. 실제 일정 변경은
              연장·조기 철수로 기록하세요.
            </div>
            <SelectInput
              label="투입 구분"
              name="asgn_se_cd"
              value={kind}
              onValueChange={(v) => {
                setKind(v);
                if (v !== "SUB") setPerson(null);
              }}
              disabled={blocked || a.rplc_asgn_id != null}
              options={kinds.codes.map((c) => ({
                value: c.code,
                label: c.name,
              }))}
              error={fieldError("asgn_se_cd")}
            />
            <TextInput
              label="투입일"
              name="bgn_dt"
              type="date"
              value={start}
              onValueChange={setStart}
              disabled={blocked}
              error={fieldError("bgn_dt")}
            />
            {kind === "SUB" && (
              <fieldset disabled={blocked || a.rplc_asgn_id != null}>
                <legend>계약상 인력</legend>
                <DeveloperSelect
                  value={person}
                  onChange={setPerson}
                  disabled={blocked || a.rplc_asgn_id != null}
                />
                {fieldError("cntrct_dev_id") && (
                  <p role="alert">{fieldError("cntrct_dev_id")}</p>
                )}
              </fieldset>
            )}
          </>
        )}
        <SelectInput
          label="사유"
          name="rsn_cd"
          required={required}
          value={reason}
          onValueChange={setReason}
          disabled={blocked || reasons.loading}
          options={reasons.codes.map((c) => ({ value: c.code, label: c.name }))}
          error={fieldError("rsn_cd")}
        />
        <TextareaInput
          label="메모"
          name="rsn_desc"
          value={memo}
          onValueChange={setMemo}
          disabled={blocked}
          maxLength={200}
          error={fieldError("rsn_desc")}
        />
        <div className={styles.actions}>
          <Button
            type="button"
            data-testid="changes-form-cancel"
            disabled={write.pending}
            onClick={cancel}
          >
            취소
          </Button>
          <Can perm="UI-12.update">
            <Button
              type="submit"
              variant="primary"
              data-testid="changes-form-save"
              pending={write.pending}
              disabled={
                blocked ||
                reasons.loading ||
                !!reasons.error ||
                boundary.loading ||
                !!boundary.error
              }
            >
              기록 저장
            </Button>
          </Can>
        </div>
      </form>
      {leaving && (
        <Discard onCancel={() => setLeaving(false)} onDiscard={onCancel} />
      )}
    </Modal>
  );
}

export function AssignmentFixForm({
  assignment,
  target,
  reload,
  onCancel,
  onSaved,
  readOnly = false,
  projectWritable = true,
}: FormProps & {
  target: AssignmentChange;
  onSaved: (result: FixResult) => void;
}) {
  const [version] = useState(assignment.updated_at);
  const [after, setAfter] = useState(""),
    [reason, setReason] = useState("");
  const [dirty, setDirty] = useState(false),
    [leaving, setLeaving] = useState(false),
    [submitted, setSubmitted] = useState(false);
  const reasons = useCodes("ASGN_CHG_RSN");
  const write = useAdminWrite(reload);
  const blocked =
    write.pending ||
    write.uncertain ||
    write.error?.code === "STALE_ASSIGNMENT" ||
    readOnly ||
    !projectWritable ||
    ["CANCELLED", "ENDED"].includes(assignment.asgn_stts_cd) ||
    ["START", "FIX"].includes(target.asgn_chg_ty_cd) ||
    target.fixed_yn === "Y";
  const cancel = () => {
    if (!write.pending) {
      if (dirty) setLeaving(true);
      else onCancel();
    }
  };
  return (
    <Modal title="정정" onCancel={cancel}>
      <form
        className={styles.form}
        data-testid="assignment-correction-form"
        noValidate
        onChange={() => setDirty(true)}
        onSubmit={(e) => {
          e.preventDefault();
          setSubmitted(true);
          if (blocked || !after.trim() || after.trim().length > 200 || !reason)
            return;
          let result: FixResult;
          void write.save(
            async () => {
              result = await createAssignmentFix(
                assignment.asgn_id,
                target.asgn_chg_id,
                { updated_at: version, aft_val: after.trim(), rsn_cd: reason },
              );
            },
            () => onSaved(result),
          );
        }}
      >
        <p>
          대상 {target.aply_dt} · {changeNames[target.asgn_chg_ty_cd]} ·{" "}
          {target.bfr_val ?? "–"} → {target.aft_val}
        </p>
        <p>지금 값을 바꾸려면 [+ 변경 기록]을 따로 넣으세요.</p>
        <ErrorBanner error={write.error} onRetry={() => void write.recover()} />
        <ErrorBanner error={reasons.error} onRetry={reasons.retry} />
        <TextareaInput
          name="aft_val"
          label="바른 내용"
          value={after}
          onValueChange={setAfter}
          maxLength={200}
          required
          disabled={blocked}
          error={
            write.error?.field === "aft_val"
              ? write.error.message
              : submitted && !after.trim()
                ? "바른 내용을 적어 주세요."
                : null
          }
        />
        <SelectInput
          name="rsn_cd"
          label="사유"
          value={reason}
          onValueChange={setReason}
          required
          disabled={blocked || reasons.loading}
          options={reasons.codes.map((c) => ({ value: c.code, label: c.name }))}
          error={
            write.error?.field === "rsn_cd"
              ? write.error.message
              : submitted && !reason
                ? "사유를 골라 주세요."
                : null
          }
        />
        <div className={styles.actions}>
          <Button
            type="button"
            data-testid="changes-correction-cancel"
            disabled={write.pending}
            onClick={cancel}
          >
            취소
          </Button>
          <Can perm="UI-12.update">
            <Button
              type="submit"
              variant="primary"
              data-testid="changes-correction-save"
              pending={write.pending}
              disabled={blocked || reasons.loading || !!reasons.error}
            >
              정정 저장
            </Button>
          </Can>
        </div>
      </form>
      {leaving && (
        <Discard onCancel={() => setLeaving(false)} onDiscard={onCancel} />
      )}
    </Modal>
  );
}
