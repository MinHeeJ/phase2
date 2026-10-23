import { useEffect, useState } from "react";
import { Button, ConfirmDialog, Modal } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { ApiFailure } from "../../shared/api";
import {
  readSalesPartDetail,
  saveSalesPartDisable,
  saveSalesPartEnable,
  saveSalesPartStandard,
  updateSalesPartName,
  type SalesPartDetail,
} from "./client";
import styles from "./SalesPartActions.module.css";

type Mode = "standard" | "name" | "disable" | "blocked" | null;
export function useSalesPartMobile() {
  const [mobile, setMobile] = useState(
    () =>
      typeof window.matchMedia === "function" &&
      window.matchMedia("(max-width: 767px)").matches,
  );
  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    const media = window.matchMedia("(max-width: 767px)");
    const change = () => setMobile(media.matches);
    media.addEventListener("change", change);
    return () => media.removeEventListener("change", change);
  }, []);
  return mobile;
}
export function SalesPartActions({
  detail,
  onChanged,
  onDetailChange,
  onDirtyChange,
}: {
  detail: SalesPartDetail;
  onChanged: () => Promise<unknown>;
  onDetailChange: (detail: SalesPartDetail) => void;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const mobile = useSalesPartMobile();
  const [mode, setMode] = useState<Mode>(null),
    [more, setMore] = useState(false),
    [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null),
    [message, setMessage] = useState("");
  const [draft, setDraft] = useState({
    bsn_part_nm: "",
    aply_bgn_ym: "",
    ldr_nm: "",
    prft_dstrb_rt: "",
    bsn_fee_amt: "",
  });
  const [dirty, setDirty] = useState(false),
    [confirmLeave, setConfirmLeave] = useState(false);
  const [local, setLocal] = useState<{ field: string; message: string } | null>(
    null,
  );
  useEffect(() => {
    onDirtyChange?.(dirty);
    return () => onDirtyChange?.(false);
  }, [dirty, onDirtyChange]);
  const close = () => {
    if (pending) return;
    if (dirty) setConfirmLeave(true);
    else setMode(null);
  };
  const open = (next: Mode) => {
    const current = detail.currentStandard;
    setDraft({
      bsn_part_nm: detail.bsn_part_nm,
      aply_bgn_ym: detail.firstAllowedMonth ?? detail.referenceMonth,
      ldr_nm: current?.ldr_nm ?? "",
      prft_dstrb_rt: current ? String(current.prft_dstrb_rt) : "",
      bsn_fee_amt: current ? String(current.bsn_fee_amt) : "",
    });
    setError(null);
    setLocal(null);
    setDirty(false);
    setMore(false);
    setMode(next);
  };
  const reload = async () => {
    const latest = await readSalesPartDetail(detail.bsn_part_id);
    onDetailChange(latest);
    await onChanged();
  };
  const write = async (
    action: () => Promise<SalesPartDetail>,
    success: string,
  ) => {
    if (pending || error?.uncertainWrite) return;
    setPending(true);
    setError(null);
    try {
      const saved = await action();
      onDetailChange(saved);
      setDirty(false);
      setMode(null);
      setMessage(success);
      await reload();
    } catch (caught) {
      const failure =
        caught instanceof ApiFailure
          ? caught
          : new ApiFailure("처리하지 못했습니다.", null, null);
      setError(failure);
      if (failure.code === "PART_IN_USE") setMode("blocked");
    } finally {
      setPending(false);
    }
  };
  const validate = () => {
    if (mode === "name") {
      if (!draft.bsn_part_nm.trim() || draft.bsn_part_nm.length > 30)
        return { field: "bsn_part_nm", message: "파트명을 입력해 주세요." };
      return null;
    }
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(draft.aply_bgn_ym))
      return { field: "aply_bgn_ym", message: "적용 시작월을 입력해 주세요." };
    if (
      detail.firstAllowedMonth &&
      draft.aply_bgn_ym < detail.firstAllowedMonth
    )
      return {
        field: "aply_bgn_ym",
        message: `적용 시작월은 ${detail.firstAllowedMonth}부터 고를 수 있습니다.`,
      };
    if (!draft.ldr_nm.trim() || draft.ldr_nm.length > 50)
      return { field: "ldr_nm", message: "파트장을 입력해 주세요." };
    if (
      !/^\d+(\.\d{1,2})?$/.test(draft.prft_dstrb_rt) ||
      Number(draft.prft_dstrb_rt) > 100
    )
      return {
        field: "prft_dstrb_rt",
        message: "이익 배분율은 0~100 사이 숫자로 입력해 주세요.",
      };
    if (
      !/^\d+$/.test(draft.bsn_fee_amt) ||
      !Number.isSafeInteger(Number(draft.bsn_fee_amt)) ||
      Number(draft.bsn_fee_amt) < 1
    )
      return { field: "bsn_fee_amt", message: "영업비를 입력해 주세요." };
    return null;
  };
  const submit = () => {
    const invalid = validate();
    setLocal(invalid);
    if (invalid) return;
    if (mode === "name") {
      if (draft.bsn_part_nm === detail.bsn_part_nm) {
        setMode(null);
        setDirty(false);
        return;
      }
      void write(
        () => updateSalesPartName(detail.bsn_part_id, draft.bsn_part_nm),
        "파트명을 바꿨습니다.",
      );
    } else
      void write(
        () =>
          saveSalesPartStandard(detail.bsn_part_id, {
            aply_bgn_ym: draft.aply_bgn_ym,
            ldr_nm: draft.ldr_nm,
            prft_dstrb_rt: Number(draft.prft_dstrb_rt),
            bsn_fee_amt: Number(draft.bsn_fee_amt),
          }),
        `${detail.bsn_part_nm} 기준을 바꿨습니다. ${draft.aply_bgn_ym}부터 적용됩니다.`,
      );
  };
  const field = (
    key: keyof typeof draft,
    label: string,
    maxLength?: number,
    type = "text",
  ) => {
    const failure =
      local?.field === key
        ? local.message
        : error?.field === key
          ? error.message
          : null;
    return (
      <label className={styles.field} key={key}>
        {label}
        <input
          data-testid={`sales-part-${key.replaceAll("_", "-")}`}
          name={key}
          aria-label={label}
          aria-invalid={!!failure}
          type={type}
          value={draft[key]}
          maxLength={maxLength}
          min={
            type === "month"
              ? (detail.firstAllowedMonth ?? undefined)
              : undefined
          }
          disabled={pending}
          onChange={(event) => {
            setDraft({ ...draft, [key]: event.target.value });
            setDirty(true);
            setLocal(null);
            if (!error?.uncertainWrite) setError(null);
          }}
        />
        {failure && <span role="alert">{failure}</span>}
      </label>
    );
  };
  return (
    <section
      data-testid="sales-part-actions"
      aria-label="영업 파트 작업"
      className={styles.region}
    >
      {message && <p role="status">{message}</p>}
      {!mobile && detail.hq_yn !== "Y" && (
        <div className={styles.writeActions}>
          <Button
            data-testid="sales-part-standard-open"
            onClick={() => open("standard")}
          >
            기준 변경
          </Button>
          <Button
            data-testid="sales-part-more"
            aria-expanded={more}
            onClick={() => setMore(!more)}
          >
            더보기
          </Button>
          {more && (
            <div>
              <Button
                data-testid="sales-part-name-open"
                onClick={() => open("name")}
              >
                파트명 수정
              </Button>
              {detail.use_yn === "Y" ? (
                <Button
                  data-testid="sales-part-disable-open"
                  onClick={() =>
                    open(
                      detail.peopleCount || detail.projectCount
                        ? "blocked"
                        : "disable",
                    )
                  }
                >
                  사용 중지
                </Button>
              ) : (
                <Button
                  data-testid="sales-part-enable"
                  pending={pending}
                  onClick={() => {
                    void write(
                      () => saveSalesPartEnable(detail.bsn_part_id),
                      `${detail.bsn_part_nm}을 다시 사용합니다.`,
                    );
                  }}
                >
                  다시 사용
                </Button>
              )}
            </div>
          )}
        </div>
      )}
      {error && !mode && (
        <ErrorBanner
          error={error}
          onRetry={() => {
            void reload()
              .then(() => setError(null))
              .catch(() => {});
          }}
        />
      )}
      {!mobile && (mode === "name" || mode === "standard") && (
        <Modal
          title={
            mode === "name" ? "파트명 수정" : `${detail.bsn_part_nm} 기준 변경`
          }
          onCancel={close}
        >
          <form
            data-testid="sales-part-edit-dialog"
            data-screen-id={mode === "name" ? "SCR-UI-15-M9" : "SCR-UI-15-M8"}
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              submit();
            }}
          >
            {mode === "name" ? (
              <>
                {field("bsn_part_nm", "파트명", 30)}
                <p>소속 인력·프로젝트·사업계획의 파트명도 함께 바뀝니다.</p>
              </>
            ) : (
              <>
                <p>기준 변경은 시스템 관리자·재무 담당만 할 수 있습니다.</p>
                {field("aply_bgn_ym", "적용 시작월", undefined, "month")}
                <p>
                  {detail.lastClosedMonth
                    ? `마감된 달(${detail.lastClosedMonth}) 다음 달부터 고를 수 있습니다.`
                    : "마감 이력이 없어 현재 서울월부터 고를 수 있습니다."}
                </p>
                {field("ldr_nm", "파트장", 50)}
                {field("prft_dstrb_rt", "이익 배분율 (%)")}
                {field("bsn_fee_amt", "영업비 (원)")}
              </>
            )}
            <ErrorBanner
              error={error}
              pending={pending}
              onRetry={() => {
                void reload()
                  .then(() => setError(null))
                  .catch(() => {});
              }}
            />
            <div className={styles.buttons}>
              <Button
                type="button"
                data-testid="sales-part-edit-cancel"
                disabled={pending}
                onClick={close}
              >
                취소
              </Button>
              <Button
                data-testid="sales-part-edit-save"
                type="submit"
                variant="primary"
                pending={pending}
                disabled={error?.uncertainWrite}
              >
                저장
              </Button>
            </div>
          </form>
        </Modal>
      )}
      {!mobile && mode === "disable" && (
        <ConfirmDialog
          title={`${detail.bsn_part_nm}의 사용을 중지할까요?`}
          destructive
          confirmLabel="사용 중지"
          onCancel={close}
          onConfirm={() => {
            void write(
              () => saveSalesPartDisable(detail.bsn_part_id),
              "사용을 중지했습니다.",
            );
          }}
        >
          중지하면 새 인력·프로젝트에 고를 수 없습니다.
          {error && <span role="alert">{error.message}</span>}
        </ConfirmDialog>
      )}
      {!mobile && mode === "blocked" && (
        <Modal title="사용을 중지할 수 없습니다" onCancel={close}>
          <div
            data-testid="sales-part-blocked-dialog"
            data-screen-id="SCR-UI-15-M12"
          >
            <p>
              {error?.code === "PART_IN_USE"
                ? error.message
                : `${detail.bsn_part_nm}에 소속 인력 ${detail.peopleCount}명, 진행 프로젝트 ${detail.projectCount}건이 있습니다. 먼저 다른 파트로 옮겨 주세요.`}
            </p>
            <Button
              data-testid="sales-part-blocked-close"
              onClick={() => {
                setMode(null);
                setError(null);
              }}
            >
              확인
            </Button>
          </div>
        </Modal>
      )}
      {confirmLeave && (
        <ConfirmDialog
          title="저장하지 않은 내용이 있습니다."
          confirmLabel="닫기"
          cancelLabel="계속 작성"
          onCancel={() => setConfirmLeave(false)}
          onConfirm={() => {
            setDirty(false);
            setConfirmLeave(false);
            setMode(null);
          }}
        >
          닫으면 입력한 내용이 사라집니다.
        </ConfirmDialog>
      )}
    </section>
  );
}
