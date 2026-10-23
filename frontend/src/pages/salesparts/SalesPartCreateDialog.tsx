import { useEffect, useState } from "react";
import { ApiFailure } from "../../shared/api";
import { Button, Modal, ConfirmDialog, Skeleton } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { getEffectiveParam } from "../shared/api";
import {
  createSalesPart,
  salesPartCreationMetadata,
  type SalesPartPage,
  type SalesPartDetail,
} from "./client";
import styles from "./SalesParts.module.css";

export function SalesPartCreateDialog({
  page,
  onCancel,
  onSaved,
  onDirtyChange,
}: {
  page: SalesPartPage;
  onCancel: () => void;
  onSaved: (part: SalesPartDetail) => void;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [metadata, setMetadata] = useState<SalesPartDetail | null>(null);
  const [month, setMonth] = useState("");
  const [name, setName] = useState(""),
    [leader, setLeader] = useState("");
  const [rate, setRate] = useState(""),
    [fee, setFee] = useState("");
  const [loading, setLoading] = useState(true),
    [defaultsLoading, setDefaultsLoading] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null),
    [pending, setPending] = useState(false);
  const [dirty, setDirty] = useState(false),
    [confirm, setConfirm] = useState(false),
    [generation, setGeneration] = useState(0);
  const [uncertain, setUncertain] = useState(false);
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    void salesPartCreationMetadata(page)
      .then((data) => {
        if (!alive) return;
        if (!data.firstAllowedMonth)
          throw new Error("적용 시작월을 확인할 수 없습니다.");
        setMetadata(data);
        setMonth(data.firstAllowedMonth);
      })
      .catch((e: unknown) => {
        if (alive) setError(failure(e));
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [page, generation]);
  useEffect(() => {
    if (
      !month ||
      !metadata?.firstAllowedMonth ||
      month < metadata.firstAllowedMonth
    )
      return;
    let alive = true;
    setDefaultsLoading(true);
    setError(null);
    setRate("");
    setFee("");
    void Promise.all([
      getEffectiveParam("PART_PROFIT_RATE", month),
      getEffectiveParam("PART_SALES_FEE", month),
    ])
      .then(([r, f]) => {
        if (!alive) return;
        if (r.num_val == null || f.num_val == null)
          throw new Error("운영 기준값을 확인할 수 없습니다.");
        setRate(String(r.num_val));
        setFee(String(f.num_val));
      })
      .catch((e: unknown) => {
        if (alive) setError(failure(e));
      })
      .finally(() => {
        if (alive) setDefaultsLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [month, metadata]);
  const cancel = () => {
    if (pending) return;
    if (dirty) setConfirm(true);
    else onCancel();
  };
  const change = (set: (value: string) => void, value: string) => {
    set(value);
    setDirty(true);
    setError(null);
  };
  const save = async () => {
    if (pending || loading || defaultsLoading || uncertain) return;
    let field: string | null = null,
      message = "";
    if (!name.trim() || name.length > 30) {
      field = "bsn_part_nm";
      message = "파트명을 입력해 주세요.";
    } else if (
      !month ||
      !metadata?.firstAllowedMonth ||
      month < metadata.firstAllowedMonth
    ) {
      field = "aply_bgn_ym";
      message = "마감된 달 다음 달부터 고를 수 있습니다.";
    } else if (!leader.trim() || leader.length > 50) {
      field = "ldr_nm";
      message = "파트장을 입력해 주세요.";
    } else if (!/^\d+(\.\d{1,2})?$/.test(rate) || Number(rate) > 100) {
      field = "prft_dstrb_rt";
      message = "이익 배분율은 0~100 사이 숫자로 입력해 주세요.";
    } else if (
      !/^\d+$/.test(fee) ||
      !Number.isSafeInteger(Number(fee)) ||
      Number(fee) < 1
    ) {
      field = "bsn_fee_amt";
      message = "영업비를 입력해 주세요.";
    }
    if (field) {
      setError(new ApiFailure(message, 400, null, field));
      return;
    }
    setPending(true);
    setError(null);
    try {
      const part = await createSalesPart({
        bsn_part_nm: name,
        aply_bgn_ym: month,
        ldr_nm: leader,
        prft_dstrb_rt: Number(rate),
        bsn_fee_amt: Number(fee),
      });
      onSaved(part);
    } catch (e) {
      const err = failure(e);
      setError(err);
      setUncertain(err.uncertainWrite);
    } finally {
      setPending(false);
    }
  };
  const field = (
    key: string,
    label: string,
    value: string,
    set: (value: string) => void,
    maxLength?: number,
  ) => (
    <label className={styles.field} key={key}>
      {label}
      <input
        data-testid={`sales-part-${key.replaceAll("_", "-")}`}
        aria-label={label}
        value={value}
        maxLength={maxLength}
        disabled={
          pending ||
          loading ||
          ((key === "prft_dstrb_rt" || key === "bsn_fee_amt") &&
            defaultsLoading)
        }
        required
        aria-invalid={error?.field === key}
        onChange={(e) => change(set, e.target.value)}
      />
      {error?.field === key && <span role="alert">{error.message}</span>}
    </label>
  );
  return (
    <Modal title="영업 파트 추가" onCancel={cancel}>
      <section
        data-testid="sales-part-create-dialog"
        data-screen-id="SCR-UI-15-M7"
      >
        {loading && <Skeleton />}
        <ErrorBanner
          error={error}
          pending={pending}
          onRetry={() => {
            if (uncertain) {
              onCancel();
              return;
            }
            setGeneration((n) => n + 1);
          }}
        />
        <form
          noValidate
          onSubmit={(e) => {
            e.preventDefault();
            void save();
          }}
        >
          {field("bsn_part_nm", "파트명", name, setName, 30)}
          <label className={styles.field}>
            적용 시작월
            <input
              data-testid="sales-part-start-month"
              aria-label="적용 시작월"
              type="month"
              required
              value={month}
              min={metadata?.firstAllowedMonth ?? undefined}
              disabled={loading || pending}
              onChange={(e) => change(setMonth, e.target.value)}
            />
            {error?.field === "aply_bgn_ym" && (
              <span role="alert">{error.message}</span>
            )}
          </label>
          {metadata && (
            <p>
              {metadata.lastClosedMonth
                ? `마감된 달(${metadata.lastClosedMonth}) 다음 달부터 고를 수 있습니다.`
                : `마감 이력이 없어 현재 월(${metadata.firstAllowedMonth})부터 고를 수 있습니다.`}
            </p>
          )}
          {field("ldr_nm", "파트장", leader, setLeader, 50)}
          {field("prft_dstrb_rt", "이익 배분율(%)", rate, setRate)}
          {field("bsn_fee_amt", "영업비(원)", fee, setFee)}
          {defaultsLoading && <p role="status">운영 기준값을 불러오는 중</p>}
          <div>
            <Button
              data-testid="sales-part-create-cancel"
              type="button"
              disabled={pending}
              onClick={cancel}
            >
              취소
            </Button>
            <Button
              data-testid="sales-part-create-submit"
              type="submit"
              variant="primary"
              pending={pending}
              disabled={loading || defaultsLoading || !metadata || uncertain}
            >
              추가
            </Button>
          </div>
        </form>
        {confirm && (
          <ConfirmDialog
            title="작성 중인 내용을 닫을까요?"
            confirmLabel="닫기"
            cancelLabel="계속 작성"
            onCancel={() => setConfirm(false)}
            onConfirm={onCancel}
          >
            저장하지 않은 입력은 사라집니다.
          </ConfirmDialog>
        )}
      </section>
    </Modal>
  );
}
export function failure(error: unknown): ApiFailure {
  return error instanceof ApiFailure
    ? error
    : new ApiFailure(
        error instanceof Error ? error.message : "조회하지 못했습니다.",
        null,
        null,
      );
}
