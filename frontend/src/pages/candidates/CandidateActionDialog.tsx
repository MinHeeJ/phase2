import { useState } from "react";
import { Button, Modal } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminWrite } from "../admin/useAdminResource";
import { useCodes } from "../shared/useCodes";
import { emptyDeveloper } from "../developers/client";
import {
  convertCandidate,
  createCandidateContact,
  type CandidateDetail,
} from "./client";
import styles from "./Candidates.module.css";

export function CandidateActionDialog({
  row,
  action,
  onClose,
  onSaved,
}: {
  row: CandidateDetail;
  action: "contact" | "convert";
  onClose: () => void;
  onSaved: () => Promise<unknown>;
}) {
  const statuses = useCodes("CONTACT_STATUS");
  const [date, setDate] = useState(""),
    [status, setStatus] = useState("NONE"),
    [memo, setMemo] = useState("");
  const [number, setNumber] = useState(""),
    [hire, setHire] = useState(""),
    [message, setMessage] = useState("");
  const write = useAdminWrite(onSaved);
  const submit = async () => {
    setMessage("");
    if (action === "contact") {
      const today = new Intl.DateTimeFormat("sv-SE", {
        timeZone: "Asia/Seoul",
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
      }).format(new Date());
      if (!date || date > today) {
        setMessage("연락일은 오늘까지의 날짜를 입력하세요.");
        return;
      }
      if (!memo.trim() || memo.trim().length > 1000) {
        setMessage("메모는 1~1000자로 입력하세요.");
        return;
      }
      await write.save(
        () =>
          createCandidateContact(row.cand_id, {
            cntct_dt: date,
            cntct_stts_cd: status,
            memo: memo.trim(),
          }),
        onClose,
      );
    } else {
      if (number && !/^[A-Z0-9-]{1,20}$/.test(number)) {
        setMessage("관리번호는 영문 대문자·숫자·하이픈 1~20자입니다.");
        return;
      }
      await write.save(
        () =>
          convertCandidate(row.cand_id, {
            ...emptyDeveloper,
            mng_no: number || null,
            dev_nm: row.cand_nm,
            emp_se_cd: row.cand_se_cd,
            main_job_cd: row.job_cd,
            vndr_ptnr_id: row.vndr_ptnr_id,
            grd_cd: row.grd_cd,
            skill: row.skill,
            tel_no: row.tel_no,
            hire_dt: hire || null,
            dflt_untprc_amt: row.hope_untprc_amt,
            rmrk_desc: row.rmrk_desc,
          }),
        onClose,
      );
    }
  };
  return (
    <Modal
      title={action === "contact" ? "연락 기록 추가" : "개발자로 등록"}
      onCancel={() => {
        if (!write.pending) onClose();
      }}
    >
      <section data-testid="candidate-action-dialog" className={styles.form}>
        <ErrorBanner
          error={write.error}
          onRetry={write.uncertain ? write.recover : undefined}
        />
        {message && <p role="alert">{message}</p>}
        {write.error?.field && (
          <p role="alert">
            {write.error.field}: {write.error.message}
          </p>
        )}
        <form
          data-testid="candidate-action-form"
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          {action === "contact" ? (
            <>
              <ErrorBanner error={statuses.error} onRetry={statuses.retry} />
              <label>
                연락일
                <input
                  data-testid="candidate-contact-date"
                  type="date"
                  value={date}
                  onChange={(e) => setDate(e.target.value)}
                />
              </label>
              <label>
                연락 상태
                <select
                  data-testid="candidate-contact-status"
                  value={status}
                  onChange={(e) => setStatus(e.target.value)}
                >
                  {statuses.codes.map((c) => (
                    <option
                      key={c.code}
                      value={c.code}
                      data-testid={`candidate-contact-status-option-${c.code.toLowerCase().replaceAll("_", "-")}`}
                    >
                      {c.name}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                연락 메모
                <textarea
                  data-testid="candidate-contact-memo"
                  maxLength={1000}
                  value={memo}
                  onChange={(e) => setMemo(e.target.value)}
                />
              </label>
            </>
          ) : (
            <>
              <p>
                {row.cand_nm} · {row.cand_se_cd} · {row.job_cd} ·{" "}
                {row.grd_cd ?? "–"}
              </p>
              <p>
                후보의 소속·직무·등급·스킬로 새 개발자를 등록합니다.
                급여·원가·투입은 생성하지 않습니다.
              </p>
              <label>
                개발자 관리번호
                <input
                  data-testid="candidate-developer-number"
                  placeholder="비우면 자동 생성"
                  value={number}
                  maxLength={20}
                  onChange={(e) => setNumber(e.target.value)}
                />
              </label>
              <label>
                입사일
                <input
                  data-testid="candidate-developer-hire"
                  type="date"
                  value={hire}
                  onChange={(e) => setHire(e.target.value)}
                />
              </label>
            </>
          )}
          <Button
            data-testid="candidate-action-cancel"
            type="button"
            disabled={write.pending}
            onClick={onClose}
          >
            취소
          </Button>
          <Button
            data-testid="candidate-action-save"
            type="submit"
            variant="primary"
            pending={write.pending}
            disabled={
              write.uncertain || (action === "convert" && row.dev_id != null)
            }
          >
            저장
          </Button>
        </form>
      </section>
    </Modal>
  );
}
