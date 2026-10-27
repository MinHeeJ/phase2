import { useCallback, useEffect, useState } from "react";
import { Button, Modal, Skeleton } from "../../shared/Controls";
import { SelectInput, TextInput, TextareaInput } from "../../shared/Inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import { listDeveloperOptions } from "../developersops/client";
import type { AssignmentSelection, ReplacementResult } from "./client";
import { createAssignmentReplacement } from "./client";
import styles from "./AssignmentDialogs.module.css";

function tomorrow(today: string) {
  const date = new Date(`${today}T12:00:00Z`);
  date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString().slice(0, 10);
}
export function AssignmentReplacementDialog({
  assignment,
  today,
  onCancel,
  onSaved,
  reload,
  onDirty,
}: {
  assignment: AssignmentSelection;
  today: string;
  onCancel: () => void;
  onSaved: (result: ReplacementResult) => void;
  reload: () => Promise<unknown>;
  onDirty?: (dirty: boolean) => void;
}) {
  const [date, setDate] = useState(() => tomorrow(today));
  const [person, setPerson] = useState("");
  const [reason, setReason] = useState("");
  const [description, setDescription] = useState("");
  const [query, setQuery] = useState("");
  const [dirty, setDirty] = useState(false),
    [leaving, setLeaving] = useState(false);
  const [invalid, setInvalid] = useState<string | null>(null);
  const people = useAdminRead(
    useCallback(() => listDeveloperOptions(query), [query]),
  );
  const reasons = useCodes("ASGN_CHG_RSN");
  const write = useAdminWrite(reload);
  useEffect(() => {
    onDirty?.(dirty);
    const guard = (e: BeforeUnloadEvent) => {
      if (dirty) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", guard);
    return () => {
      window.removeEventListener("beforeunload", guard);
      onDirty?.(false);
    };
  }, [dirty, onDirty]);
  const change = () => {
    setDirty(true);
    setInvalid(null);
    write.clear();
  };
  const cancel = () => {
    if (!write.pending) {
      if (dirty) setLeaving(true);
      else onCancel();
    }
  };
  const error = (field: string) =>
    invalid === field
      ? "필수 항목과 입력 범위를 확인하세요."
      : write.error?.field === field
        ? write.error.message
        : null;
  const blocked = write.pending || write.uncertain;
  const save = () => {
    const field =
      !date || date <= assignment.bgn_dt || date > assignment.end_dt
        ? "aply_dt"
        : !person || Number(person) === assignment.dev_id
          ? "dev_id"
          : !reason
            ? "rsn_cd"
            : "";
    if (field) {
      setInvalid(field);
      return;
    }
    let result: ReplacementResult;
    void write.save(
      async () => {
        result = await createAssignmentReplacement(assignment.asgn_id, {
          updated_at: assignment.updated_at,
          aply_dt: date,
          dev_id: Number(person),
          rsn_cd: reason,
          rsn_desc: description || null,
        });
      },
      () => {
        setDirty(false);
        onSaved(result);
      },
    );
  };
  return (
    <Modal title="교체" onCancel={cancel}>
      <form
        data-screen-id="SCR-UI-12-M3"
        data-testid="assignment-replacement-dialog"
        className={styles.form}
        onSubmit={(e) => {
          e.preventDefault();
          save();
        }}
        noValidate
      >
        <ErrorBanner
          error={write.error}
          pending={write.pending}
          onRetry={() => void write.recover()}
        />
        <ErrorBanner
          error={people.error}
          onRetry={() => void people.reload().catch(() => {})}
        />
        <ErrorBanner error={reasons.error} onRetry={reasons.retry} />
        <TextInput
          name="aply_dt"
          label="교체일"
          type="date"
          required
          value={date}
          disabled={blocked}
          error={error("aply_dt")}
          onValueChange={(v) => {
            setDate(v);
            change();
          }}
        />
        <TextInput
          name="person_query"
          label="이름·관리번호 찾기"
          value={query}
          disabled={blocked}
          onValueChange={setQuery}
        />
        {people.loading ? (
          <Skeleton />
        ) : (
          <SelectInput
            name="dev_id"
            label="들어오는 사람"
            required
            value={person}
            disabled={blocked || !!people.error}
            error={error("dev_id")}
            options={(people.data ?? [])
              .filter((p) => p.id !== assignment.dev_id)
              .map((p) => ({
                value: String(p.id),
                label: `${p.name} · ${p.mngNo}`,
              }))}
            onValueChange={(v) => {
              setPerson(v);
              change();
            }}
          />
        )}
        {!people.loading &&
          !people.error &&
          people.data?.filter((p) => p.id !== assignment.dev_id).length ===
            0 && <p>선택할 개발자가 없습니다.</p>}
        <SelectInput
          name="rsn_cd"
          label="사유"
          required
          value={reason}
          disabled={blocked || reasons.loading || !!reasons.error}
          error={error("rsn_cd")}
          options={reasons.codes.map((c) => ({ value: c.code, label: c.name }))}
          onValueChange={(v) => {
            setReason(v);
            change();
          }}
        />
        <TextareaInput
          name="rsn_desc"
          label="메모"
          value={description}
          maxLength={200}
          disabled={blocked}
          error={error("rsn_desc")}
          onValueChange={(v) => {
            setDescription(v);
            change();
          }}
        />
        <div className={styles.warning}>
          <p>
            교체일 전날까지 {assignment.dev_nm}, 교체일부터{" "}
            {people.data?.find((p) => String(p.id) === person)?.name ??
              "들어오는 사람"}
            .
          </p>
          <p>
            청구서에는 계약상 인력{" "}
            {assignment.cntrct_dev_nm ?? assignment.dev_nm}이 그대로 나갑니다.
          </p>
          <p>교체일 기준 단가와 남은 투입 기간의 예정 단가를 승계합니다.</p>
        </div>
        <div className={styles.actions}>
          <Button
            type="button"
            data-testid="assignment-replace-cancel"
            disabled={write.pending}
            onClick={cancel}
          >
            취소
          </Button>
          <Can perm="UI-12.update">
            <Button
              type="submit"
              data-testid="assignment-replace-save"
              variant="primary"
              pending={write.pending}
              disabled={
                write.uncertain ||
                people.loading ||
                !!people.error ||
                reasons.loading ||
                !!reasons.error
              }
            >
              교체 저장
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
          <div
            data-testid="assignment-discard-dialog"
            className={styles.actions}
          >
            <Button
              data-testid="assignment-keep-writing"
              onClick={() => setLeaving(false)}
            >
              계속 작성
            </Button>
            <Button data-testid="assignment-discard" onClick={onCancel}>
              나가기
            </Button>
          </div>
        </Modal>
      )}
    </Modal>
  );
}
