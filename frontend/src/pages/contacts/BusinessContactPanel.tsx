import { useEffect, useRef, useState } from "react";
import { Button } from "../../shared/Controls";
import { ApiFailure, readData } from "../../shared/api";
import { ErrorBanner } from "../../shared/ErrorBanner";
import styles from "../systemadmin/SystemAdministration.module.css";

export function BusinessContactPanel({
  id,
  kind,
}: {
  id: string | number;
  kind: "developers" | "partners";
}) {
  const [purpose, setPurpose] = useState(""),
    [contact, setContact] = useState<{
      phone: string | null;
      email: string | null;
    } | null>(null);
  const [error, setError] = useState<ApiFailure | null>(null),
    [busy, setBusy] = useState(false);
  const active = useRef<AbortController>();
  useEffect(() => {
    setContact(null);
    setPurpose("");
    return () => active.current?.abort();
  }, [id, kind]);
  const clear = () => {
    active.current?.abort();
    setContact(null);
    setError(null);
    setBusy(false);
  };
  useEffect(() => {
    const clearOnLoss = () => clear();
    window.addEventListener("permissions-changed", clearOnLoss);
    window.addEventListener("session-expired", clearOnLoss);
    return () => {
      window.removeEventListener("permissions-changed", clearOnLoss);
      window.removeEventListener("session-expired", clearOnLoss);
    };
  }, []);
  const view = async () => {
    clear();
    const controller = new AbortController();
    active.current = controller;
    setBusy(true);
    try {
      const result = await readData<{
        phone: string | null;
        email: string | null;
      }>(
        `/api/${kind}/${encodeURIComponent(String(id))}/contact?purpose=${encodeURIComponent(purpose)}`,
        controller.signal,
      );
      if (!controller.signal.aborted) setContact(result);
    } catch (e) {
      if (!controller.signal.aborted) setError(e as ApiFailure);
    } finally {
      if (!controller.signal.aborted) setBusy(false);
    }
  };
  return (
    <section
      className={`${styles.panel} ${styles.fields}`}
      data-testid={`${kind}-contact-panel`}
      aria-label="업무 연락처 원문 열람"
    >
      <h3>업무 연락처</h3>
      <p>업무 목적·담당 범위 확인 후 원문을 표시하며 열람 기록을 남깁니다.</p>
      <label>
        열람 목적
        <select
          data-testid={`${kind}-contact-purpose`}
          value={purpose}
          onChange={(e) => {
            clear();
            setPurpose(e.target.value);
          }}
        >
          <option value="">목적 선택</option>
          {kind === "developers" ? (
            <option value="STAFF_OPERATION">담당 인력 운영</option>
          ) : (
            <>
              <option value="CONTRACT_SALES">담당 계약·영업</option>
              <option value="BILLING">청구</option>
              <option value="RECEIPT">입금</option>
              <option value="PAYMENT">지급</option>
            </>
          )}
        </select>
      </label>
      <Button
        data-testid={`${kind}-contact-view`}
        disabled={!purpose}
        pending={busy}
        onClick={() => void view()}
      >
        원문 보기
      </Button>
      <ErrorBanner error={error} />
      {contact && (
        <div data-testid={`${kind}-contact-result`}>
          <p>전화: {contact.phone ?? "등록 없음"}</p>
          <p>이메일: {contact.email ?? "등록 없음"}</p>
          <Button data-testid={`${kind}-contact-close`} onClick={clear}>
            원문 닫기
          </Button>
        </div>
      )}
    </section>
  );
}
