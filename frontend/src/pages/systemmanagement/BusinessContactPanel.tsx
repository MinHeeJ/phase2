import { useEffect, useRef, useState } from "react";
import { readData, ApiFailure } from "../../shared/api";
import { Button } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";

export function BusinessContactPanel({
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
  } | null>(null);
  const [error, setError] = useState<ApiFailure | null>(null),
    [pending, setPending] = useState(false);
  const request = useRef<AbortController | null>(null);
  useEffect(() => {
    setContact(null);
    setError(null);
    return () => request.current?.abort();
  }, [id]);
  return (
    <section data-testid={`${kind}-business-contact-panel`}>
      <h3>업무 연락처</h3>
      <p>
        계정 연락처·로그인·MFA 정보와 별개로 목적과 현재 담당 범위를 검사합니다.
      </p>
      <label>
        업무 목적
        <select
          data-testid={`${kind}-contact-purpose`}
          value={purpose}
          onChange={(e) => {
            setContact(null);
            setPurpose(e.target.value);
          }}
        >
          {kind === "developers" ? (
            <option value="STAFF_OPERATION">담당 인력 운영</option>
          ) : (
            <>
              <option value="CONTRACT_SALES">계약·영업</option>
              <option value="BILLING_RECEIPT_PAYMENT">청구·입금·지급</option>
            </>
          )}
        </select>
      </label>
      <Button
        data-testid={`${kind}-contact-reveal`}
        disabled={pending}
        onClick={async () => {
          request.current?.abort();
          const controller = new AbortController();
          request.current = controller;
          setContact(null);
          setError(null);
          setPending(true);
          try {
            const value = await readData<{
              phone: string | null;
              email: string | null;
              masked: boolean;
            }>(
              `/api/${kind}/${encodeURIComponent(id)}/contact?${new URLSearchParams({ purpose })}`,
              controller.signal,
            );
            if (!controller.signal.aborted) setContact(value);
          } catch (e) {
            if (!controller.signal.aborted) setError(e as ApiFailure);
          } finally {
            if (!controller.signal.aborted) setPending(false);
          }
        }}
      >
        원문 보기
      </Button>
      {contact && (
        <div data-testid={`${kind}-contact-result`}>
          {contact.masked ? (
            <p>비활성 대상의 연락처는 비공개입니다.</p>
          ) : (
            <>
              <p>전화: {contact.phone ?? "등록 없음"}</p>
              <p>이메일: {contact.email ?? "등록 없음"}</p>
            </>
          )}
          <Button
            data-testid={`${kind}-contact-close`}
            onClick={() => setContact(null)}
          >
            원문 닫기
          </Button>
        </div>
      )}
      <ErrorBanner error={error} />
    </section>
  );
}
