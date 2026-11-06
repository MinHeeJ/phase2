import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "../../shared/Controls";
import { readData } from "../../shared/api";
import styles from "../security/Security.module.css";

export function BusinessContact({
  kind,
  id,
}: {
  kind: "developers" | "partners";
  id: number | string;
}) {
  const [purpose, setPurpose] = useState(
    kind === "developers" ? "STAFF_OPERATION" : "CONTRACT_SALES",
  );
  const [contact, setContact] = useState<{
      phone: string | null;
      email: string | null;
      masked: boolean;
    } | null>(null),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const pending = useRef<AbortController | null>(null);
  const clear = useCallback(() => {
    pending.current?.abort();
    pending.current = null;
    setContact(null);
    setError("");
    setBusy(false);
  }, []);
  useEffect(() => {
    clear();
    return () => {
      pending.current?.abort();
      pending.current = null;
    };
  }, [kind, id, purpose, clear]);
  useEffect(() => {
    window.addEventListener("sicost-session-expired", clear);
    window.addEventListener("sicost:permissions-changed", clear);
    return () => {
      window.removeEventListener("sicost-session-expired", clear);
      window.removeEventListener("sicost:permissions-changed", clear);
    };
  }, [clear]);
  const open = async () => {
    clear();
    const request = new AbortController();
    pending.current = request;
    setBusy(true);
    try {
      const result = await readData<NonNullable<typeof contact>>(
        `/api/${kind}/${encodeURIComponent(id)}/contact?${new URLSearchParams({ purpose })}`,
        request.signal,
      );
      if (pending.current === request && !request.signal.aborted)
        setContact(result);
    } catch (e) {
      if (pending.current === request && !request.signal.aborted)
        setError(e instanceof Error ? e.message : "연락처 조회 실패");
    } finally {
      if (pending.current === request) {
        pending.current = null;
        setBusy(false);
      }
    }
  };
  return (
    <section data-testid={`${kind}-contact-panel`} className={styles.panel}>
      <h3>업무 연락처</h3>
      <p>담당 범위·목적을 확인한 열람만 허용되며 열람 이력이 남습니다.</p>
      <label>
        업무 목적
        <select
          data-testid={`${kind}-contact-purpose-select`}
          value={purpose}
          onChange={(e) => {
            clear();
            setPurpose(e.target.value);
          }}
        >
          {kind === "developers" ? (
            <option value="STAFF_OPERATION">인력 운영</option>
          ) : (
            <>
              <option value="CONTRACT_SALES">계약·영업</option>
              <option value="BILLING_RECEIPT_PAYMENT">청구·입금·지급</option>
            </>
          )}
        </select>
      </label>
      <Button
        data-testid={`${kind}-contact-open-button`}
        disabled={busy}
        onClick={() => void open()}
      >
        원문 보기
      </Button>
      {error && <p role="alert">{error}</p>}
      {contact && (
        <div>
          <p>
            {contact.masked
              ? "비활성 대상의 연락처는 비공개입니다."
              : `전화: ${contact.phone ?? "미등록"} · 이메일: ${contact.email ?? "미등록"}`}
          </p>
          <Button data-testid={`${kind}-contact-close-button`} onClick={clear}>
            원문 닫기
          </Button>
        </div>
      )}
    </section>
  );
}
