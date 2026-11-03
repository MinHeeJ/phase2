import { useEffect, useRef, useState } from "react";
import {
  Badge,
  Button,
  ConfirmDialog,
  LockedBanner,
  Modal,
  Skeleton,
} from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { isDate, formatDateTime } from "../../lib/format";
import { useAdminWrite } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import { DeveloperForm } from "../developers/DeveloperForm";
import { emptyDeveloper, type DeveloperInput } from "../developers/client";
import { useDeveloperReadOnly } from "../developers/useDeveloperReadOnly";
import {
  createCandidateContact,
  createCandidateConversion,
  type CandidateContactDetail,
} from "./client";
import styles from "../developers/Developers.module.css";
import inputs from "../../shared/Inputs.module.css";

export function candidateSeoulToday(now = new Date()) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(now);
  const part = (key: string) => parts.find((p) => p.type === key)!.value;
  return `${part("year")}-${part("month")}-${part("day")}`;
}
export function CandidateContactStatus({
  status,
  label,
}: {
  status: string;
  label?: string;
}) {
  const tone =
    status === "INTV"
      ? "warning"
      : status === "SIGNED"
        ? "success"
        : status === "DONE"
          ? "info"
          : status === "HOLD"
            ? "locked"
            : "neutral";
  return <Badge tone={tone}>{label ?? status}</Badge>;
}

// SCR-UI-33: the contact tab of the single candidate detail drawer; history is append-only.
export function CandidateContactsPanel({
  candidate,
  onChanged,
}: {
  candidate: CandidateContactDetail;
  onChanged: () => Promise<unknown>;
}) {
  const codes = useCodes("CONTACT_STATUS"),
    readOnly = useDeveloperReadOnly();
  const [open, setOpen] = useState(false),
    [date, setDate] = useState(candidateSeoulToday),
    [status, setStatus] = useState(""),
    [memo, setMemo] = useState("");
  const [local, setLocal] = useState<{ field: string; message: string } | null>(
      null,
    ),
    [success, setSuccess] = useState("");
  const form = useRef<HTMLFormElement>(null),
    write = useAdminWrite(onChanged),
    error = local ?? write.error;
  useEffect(() => {
    if (readOnly) setOpen(false);
  }, [readOnly]);
  useEffect(() => {
    if (error?.field)
      form.current
        ?.querySelector<HTMLElement>(
          `[name="${error.field.replace(/[^a-z_]/g, "")}"]`,
        )
        ?.focus();
  }, [error]);
  const clear = () => {
    setLocal(null);
    write.clear();
    setSuccess("");
  };
  const save = () => {
    if (!isDate(date) || date > candidateSeoulToday()) {
      setLocal({
        field: "cntct_dt",
        message: "연락일은 서울 오늘 이후일 수 없습니다.",
      });
      return;
    }
    if (!status || !codes.codes.some((c) => c.code === status)) {
      setLocal({ field: "cntct_stts_cd", message: "연락 상태를 골라 주세요." });
      return;
    }
    if (!memo.trim() || [...memo].length > 1000) {
      setLocal({ field: "memo", message: "메모는 1~1000자로 입력하세요." });
      return;
    }
    void write.save(
      () =>
        createCandidateContact(candidate.cand_id, {
          cntct_dt: date,
          cntct_stts_cd: status,
          memo,
        }),
      () => {
        setMemo("");
        setOpen(false);
        setSuccess("연락 기록을 저장했습니다.");
      },
    );
  };
  return (
    <section data-testid="candidate-contacts-panel" data-screen-id="SCR-UI-33">
      <h3>연락 기록</h3>
      <p>
        현재 상태{" "}
        <CandidateContactStatus
          status={candidate.cntct_stts_cd}
          label={
            codes.codes.find((c) => c.code === candidate.cntct_stts_cd)?.name
          }
        />{" "}
        · 최근 연락일 {candidate.last_cntct_dt ?? "–"}
      </p>
      <p className={styles.help}>
        최근 실제 연락일 기준으로 상태가 반영됩니다. 과거 기록도 수정·삭제 없이
        보존합니다.
      </p>
      <ErrorBanner error={codes.error} onRetry={codes.retry} />
      <ErrorBanner error={write.error} onRetry={() => void write.recover()} />
      {success && <p role="status">{success}</p>}
      {!readOnly && (
        <div className={styles.actions}>
          <Button
            data-testid="candidate-contact-add"
            onClick={() => {
              clear();
              setOpen(true);
            }}
          >
            연락 기록 추가
          </Button>
        </div>
      )}
      {open && !readOnly && (
        <form
          ref={form}
          className={styles.form}
          data-testid="candidate-contact-form"
          noValidate
          onSubmit={(e) => {
            e.preventDefault();
            save();
          }}
        >
          <label>
            연락일
            <input
              className={inputs.input}
              data-testid="candidate-contact-date"
              name="cntct_dt"
              type="date"
              value={date}
              max={candidateSeoulToday()}
              disabled={write.pending}
              onChange={(e) => {
                clear();
                setDate(e.target.value);
              }}
              aria-invalid={error?.field === "cntct_dt"}
            />
          </label>
          <label>
            연락 상태
            <select
              className={inputs.input}
              data-testid="candidate-contact-status"
              name="cntct_stts_cd"
              value={status}
              disabled={write.pending || codes.loading || !!codes.error}
              onChange={(e) => {
                clear();
                setStatus(e.target.value);
              }}
              aria-invalid={error?.field === "cntct_stts_cd"}
            >
              <option value="">선택하세요</option>
              {codes.codes.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            메모
            <textarea
              className={inputs.input}
              data-testid="candidate-contact-memo"
              name="memo"
              value={memo}
              maxLength={1000}
              disabled={write.pending}
              onChange={(e) => {
                clear();
                setMemo(e.target.value);
              }}
              aria-invalid={error?.field === "memo"}
            />
          </label>
          {error?.field && <span role="alert">{error.message}</span>}
          <div className={styles.actions}>
            <Button
              data-testid="candidate-contact-cancel"
              type="button"
              disabled={write.pending}
              onClick={() => setOpen(false)}
            >
              취소
            </Button>
            <Button
              data-testid="candidate-contact-save"
              type="submit"
              variant="primary"
              pending={write.pending}
              disabled={codes.loading || !!codes.error || write.uncertain}
            >
              저장
            </Button>
          </div>
        </form>
      )}
      <div className={styles.tableScroll}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>날짜</th>
              <th>상태</th>
              <th>메모</th>
              <th>기록자</th>
              <th>등록시각</th>
            </tr>
          </thead>
          <tbody>
            {candidate.contacts.length === 0 ? (
              <tr>
                <td colSpan={5} className={styles.empty}>
                  연락 기록이 없습니다.
                </td>
              </tr>
            ) : (
              candidate.contacts.map((c, i) => (
                <tr
                  key={`${c.created_at}-${i}`}
                  data-testid={`candidate-contact-row-${i}`}
                >
                  <td>{c.cntct_dt}</td>
                  <td>
                    <CandidateContactStatus
                      status={c.cntct_stts_cd}
                      label={
                        codes.codes.find((v) => v.code === c.cntct_stts_cd)
                          ?.name
                      }
                    />
                  </td>
                  <td
                    style={{
                      whiteSpace: "pre-wrap",
                      overflowWrap: "anywhere",
                      maxWidth: 260,
                    }}
                  >
                    {c.memo}
                  </td>
                  <td>{c.user_id ?? "–"}</td>
                  <td>{formatDateTime(c.created_at)}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}

const owned = new Set<keyof DeveloperInput>([
  "dev_nm",
  "emp_se_cd",
  "vndr_ptnr_id",
  "main_job_cd",
  "grd_cd",
  "skill",
]);
// SCR-UI-33: supplements the existing developer form, never a separate developer POST.
export function CandidateConversionAction({
  candidate,
  onChanged,
  onDeveloper,
}: {
  candidate: CandidateContactDetail;
  onChanged: () => Promise<unknown>;
  onDeveloper: (id: number) => void;
}) {
  const [open, setOpen] = useState(false),
    [dirty, setDirty] = useState(false),
    [confirm, setConfirm] = useState(false),
    [pending, setPending] = useState(false),
    [success, setSuccess] = useState(false);
  const readOnly = useDeveloperReadOnly();
  useEffect(() => {
    if (readOnly) setOpen(false);
  }, [readOnly]);
  const close = () => {
    if (pending) return;
    if (dirty) setConfirm(true);
    else setOpen(false);
  };
  if (candidate.dev_id != null)
    return (
      <section data-testid="candidate-conversion-linked">
        <LockedBanner>개발자로 등록되었습니다.</LockedBanner>
        <Button
          data-testid="candidate-linked-developer"
          onClick={() => onDeveloper(candidate.dev_id!)}
        >
          연결된 개발자 확인
        </Button>
      </section>
    );
  if (readOnly) return null;
  const initial: DeveloperInput = {
    ...emptyDeveloper,
    dev_nm: candidate.cand_nm,
    emp_se_cd: candidate.cand_se_cd,
    vndr_ptnr_id: candidate.vndr_ptnr_id,
    main_job_cd: candidate.job_cd,
    grd_cd: candidate.grd_cd,
    skill: candidate.skill,
    tel_no: candidate.tel_no,
  };
  return (
    <section data-testid="candidate-conversion-action">
      {success && <p role="status">개발자로 등록했습니다.</p>}
      <Button data-testid="candidate-convert" onClick={() => setOpen(true)}>
        개발자로 등록
      </Button>
      {open && (
        <Modal title="개발자로 등록" onCancel={close}>
          <div
            data-testid="candidate-conversion-dialog"
            data-screen-id="SCR-UI-33"
          >
            <p>
              후보의 소속·직무·등급·스킬은 유지합니다. 급여·원가·실제 투입은
              자동 생성하지 않습니다.
            </p>
            {pending && <Skeleton label="개발자 등록 중" />}
            <DeveloperForm
              initialDraft={initial}
              lockedFields={owned}
              submitLabel="개발자로 등록"
              onCancel={close}
              onDirtyChange={setDirty}
              reload={onChanged}
              submitDraft={async (draft) => {
                setPending(true);
                try {
                  return (
                    await createCandidateConversion(candidate.cand_id, draft)
                  ).developer;
                } finally {
                  setPending(false);
                }
              }}
              onSaved={() => {
                setDirty(false);
                setOpen(false);
                setSuccess(true);
              }}
            />
          </div>
        </Modal>
      )}
      {confirm && (
        <ConfirmDialog
          title="입력을 취소할까요?"
          onCancel={() => setConfirm(false)}
          onConfirm={() => {
            setConfirm(false);
            setOpen(false);
            setDirty(false);
          }}
        >
          저장하지 않은 입력이 있습니다.
        </ConfirmDialog>
      )}
    </section>
  );
}
