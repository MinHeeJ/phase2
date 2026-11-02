import { useEffect, useState } from "react";
import { Badge, Button, Modal } from "../../shared/Controls";
import tables from "../developers/Developers.module.css";
import {
  getPaymentOrders,
  savePaymentOrders,
  type PaymentOrder,
  type PaymentOrders,
} from "./client";

export function PaymentOrderPanel({
  month,
  readOnly = false,
  refreshVersion,
  onSaved,
}: {
  month: string;
  readOnly?: boolean;
  refreshVersion?: unknown;
  onSaved?: () => void;
}) {
  const [data, setData] = useState<PaymentOrders | null>(null);
  const [loadedMonth, setLoadedMonth] = useState("");
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  const [draft, setDraft] = useState<PaymentOrder[] | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState("");
  const [success, setSuccess] = useState(false);
  useEffect(() => {
    let active = true;
    setData(null);
    setError("");
    setDraft(null);
    setSuccess(false);
    getPaymentOrders(month)
      .then((value) => {
        if (active) {
          setData(value);
          setLoadedMonth(month);
        }
      })
      .catch((failure: Error) => {
        if (active) setError(failure.message);
      });
    return () => {
      active = false;
    };
  }, [month, revision, refreshVersion]);
  const current = loadedMonth === month ? data : null;
  const locked = readOnly || current?.month_status === "CLOSED";
  const save = async () => {
    if (!draft || locked || saving) return;
    if (
      draft.some(
        (row) =>
          !row.planned_date.startsWith(`${month}-`) ||
          !/^\d{4}-\d{2}-\d{2}$/.test(row.planned_date),
      )
    ) {
      setSaveError("지급 예정일은 선택한 월 안에서 입력하세요.");
      return;
    }
    setSaving(true);
    setSaveError("");
    try {
      const result = await savePaymentOrders(month, draft);
      setData(result);
      setDraft(null);
      setSuccess(true);
      onSaved?.();
    } catch (failure) {
      setSaveError((failure as Error).message);
    } finally {
      setSaving(false);
    }
  };
  return (
    <section data-testid="payment-orders-panel" aria-label="지급 차수">
      <div className={tables.toolbar}>
        <h2>지급 차수</h2>
        {current && !locked && (
          <Button
            data-testid="payment-orders-settings"
            onClick={() => {
              setSaveError("");
              setDraft(current.items.map((row) => ({ ...row })));
            }}
          >
            지급 차수 설정
          </Button>
        )}
      </div>
      {locked && (
        <p role="status">
          {current?.month_status === "CLOSED"
            ? "마감된 월은 조회만 가능합니다."
            : "과거 기간은 조회만 가능합니다."}
        </p>
      )}
      {error ? (
        <div role="alert">
          {error}{" "}
          <Button
            data-testid="payment-orders-retry"
            onClick={() => setRevision((value) => value + 1)}
          >
            다시 조회
          </Button>
        </div>
      ) : !current ? (
        <p role="status">지급 차수를 불러오는 중입니다.</p>
      ) : (
        <div className={tables.tableScroll}>
          <table className={tables.table}>
            <thead>
              <tr>
                <th>차수</th>
                <th>지급 예정일</th>
                <th>상태</th>
              </tr>
            </thead>
            <tbody>
              {current.items.length === 0 ? (
                <tr>
                  <td colSpan={3} className={tables.empty}>
                    설정된 지급 차수가 없습니다.
                  </td>
                </tr>
              ) : (
                current.items.map((row) => (
                  <tr
                    key={row.pay_odr_no}
                    data-testid={`payment-order-row-${row.pay_odr_no}`}
                  >
                    <td>{row.pay_odr_no}차</td>
                    <td>{row.planned_date}</td>
                    <td>
                      <Badge tone={row.paid ? "success" : "info"}>
                        {row.paid ? "지급" : "예정"}
                      </Badge>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      )}
      {success && <p role="status">지급 차수를 저장했습니다.</p>}
      {draft && (
        <Modal
          title="지급 차수 설정"
          onCancel={() => {
            if (!saving) setDraft(null);
          }}
        >
          <div data-testid="payment-orders-dialog" className={tables.form}>
            <p>
              월 최대 5차까지 설정할 수 있습니다. 지급 완료 건의 날짜는 변경할
              수 없습니다.
            </p>
            {draft.map((row) => (
              <label
                key={row.pay_odr_no}
                data-testid={`payment-order-field-${row.pay_odr_no}`}
              >
                {row.pay_odr_no}차 지급 예정일
                <input
                  data-testid={`payment-order-date-${row.pay_odr_no}`}
                  aria-label={`${row.pay_odr_no}차 지급 예정일`}
                  type="date"
                  value={row.planned_date}
                  disabled={saving || row.paid || locked}
                  min={`${month}-01`}
                  max={`${month}-31`}
                  onChange={(event) =>
                    setDraft(
                      draft.map((item) =>
                        item.pay_odr_no === row.pay_odr_no
                          ? { ...item, planned_date: event.target.value }
                          : item,
                      ),
                    )
                  }
                />
              </label>
            ))}
            {draft.length < 5 && (
              <Button
                data-testid="payment-order-add"
                disabled={saving || locked}
                onClick={() => {
                  const number = [1, 2, 3, 4, 5].find(
                    (value) => !draft.some((row) => row.pay_odr_no === value),
                  )!;
                  setDraft(
                    [...draft, { pay_odr_no: number, planned_date: "" }].sort(
                      (a, b) => a.pay_odr_no - b.pay_odr_no,
                    ),
                  );
                }}
              >
                차수 추가
              </Button>
            )}
            {saveError && <p role="alert">{saveError}</p>}
            <div className={tables.actions}>
              <Button
                data-testid="payment-orders-cancel"
                disabled={saving}
                onClick={() => setDraft(null)}
              >
                취소
              </Button>
              <Button
                data-testid="payment-orders-save"
                variant="primary"
                pending={saving}
                disabled={locked}
                onClick={() => void save()}
              >
                저장
              </Button>
            </div>
          </div>
        </Modal>
      )}
    </section>
  );
}
