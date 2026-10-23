import { useState } from "react";
import { ApiFailure } from "../../shared/api";
import { TextInput, MonthInput, MoneyInput } from "../../shared/Inputs";
import { Button, Modal, Badge, SuccessToast } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { AdminWorkDialog } from "../admin/AdminDialogs";
import { useAdminWrite } from "../admin/useAdminResource";
import { isMonth } from "../../lib/format";
import {
  readSalesPart,
  saveSalesPartStandard,
  updateSalesPartName,
  saveSalesPartDisable,
  saveSalesPartEnable,
  type SalesPartDetail,
} from "./client";
import styles from "./SalesPartManagement.module.css";

type DialogProps = {
  part: SalesPartDetail;
  reload: () => Promise<unknown>;
  onCancel: () => void;
  onSaved: (message: string) => void;
  onDirtyChange?: (dirty: boolean) => void;
};

// SCR-UI-15-M8: current values are copied; the immutable part name is never sent.
export function SalesPartStandardDialog({
  part,
  reload,
  onCancel,
  onSaved,
  onDirtyChange,
}: DialogProps) {
  const initial = {
    month: part.firstAllowedMonth ?? "",
    leader: part.currentStandard?.ldr_nm ?? "",
    rate: part.currentStandard?.prft_dstrb_rt.toString() ?? "",
    fee: part.currentStandard?.bsn_fee_amt ?? null,
  };
  const [draft, setDraft] = useState(initial);
  const [validation, setValidation] = useState<ApiFailure | null>(null);
  const write = useAdminWrite(reload);
  const error = validation ?? write.error;
  const fieldError = (key: string) =>
    error?.field === key ? error.message : null;
  const change = (value: Partial<typeof draft>) => {
    setValidation(null);
    write.clear();
    setDraft((old) => ({ ...old, ...value }));
  };
  const save = () => {
    let message = "",
      field = "";
    if (!isMonth(draft.month)) {
      field = "aply_bgn_ym";
      message = "적용 시작월을 선택해 주세요.";
    } else if (part.lastClosedMonth && draft.month <= part.lastClosedMonth) {
      field = "aply_bgn_ym";
      message = `적용 시작월은 마감된 달(${part.lastClosedMonth}) 다음 달부터 고를 수 있습니다.`;
    } else if (!draft.leader.trim() || draft.leader.length > 50) {
      field = "ldr_nm";
      message = "파트장을 입력해 주세요.";
    } else if (
      !/^\d+(?:\.\d{1,2})?$/.test(draft.rate) ||
      Number(draft.rate) > 100
    ) {
      field = "prft_dstrb_rt";
      message = "이익 배분율은 0~100 사이 숫자로 입력해 주세요.";
    } else if (
      draft.fee == null ||
      !Number.isSafeInteger(draft.fee) ||
      draft.fee < 1
    ) {
      field = "bsn_fee_amt";
      message = "영업비를 입력해 주세요.";
    }
    if (message) {
      setValidation(new ApiFailure(message, 400, null, field));
      return;
    }
    void write.save(
      () =>
        saveSalesPartStandard(part.bsn_part_id, {
          aply_bgn_ym: draft.month,
          ldr_nm: draft.leader,
          prft_dstrb_rt: Number(draft.rate),
          bsn_fee_amt: draft.fee as number,
        }),
      () =>
        onSaved(
          `${part.bsn_part_nm} 기준을 바꿨습니다. ${draft.month}부터 적용됩니다.`,
        ),
    );
  };
  return (
    <div data-testid="sales-part-standard-dialog" data-screen-id="SCR-UI-15-M8">
      <AdminWorkDialog
        title={`${part.bsn_part_nm} 기준 변경`}
        onCancel={onCancel}
        onSave={save}
        dirty={JSON.stringify(draft) !== JSON.stringify(initial)}
        pending={write.pending}
        error={error}
        onRetry={() => {
          setValidation(null);
          void write.recover();
        }}
        blocked={write.uncertain}
        onDirtyChange={onDirtyChange}
      >
        <MonthInput
          label="적용 시작월"
          name="aply_bgn_ym"
          required
          value={draft.month}
          onValueChange={(month) => change({ month })}
          error={fieldError("aply_bgn_ym")}
          disabled={write.pending}
        />
        <p className={styles.help}>
          {part.lastClosedMonth
            ? `마감된 달(${part.lastClosedMonth}) 다음 달부터 고를 수 있습니다.`
            : "적용 시작월을 직접 선택해 주세요."}
        </p>
        <TextInput
          label="파트장"
          name="ldr_nm"
          required
          maxLength={50}
          value={draft.leader}
          onValueChange={(leader) => change({ leader })}
          error={fieldError("ldr_nm")}
          disabled={write.pending}
        />
        <TextInput
          label="이익 배분율"
          name="prft_dstrb_rt"
          required
          value={draft.rate}
          onValueChange={(rate) => change({ rate })}
          error={fieldError("prft_dstrb_rt")}
          disabled={write.pending}
        />
        <p className={styles.help}>% · 0~100, 소수 둘째 자리까지</p>
        <MoneyInput
          label="영업비"
          name="bsn_fee_amt"
          required
          value={draft.fee}
          onValueChange={(fee) => change({ fee })}
          error={fieldError("bsn_fee_amt")}
          disabled={write.pending}
        />
        <p className={styles.help}>
          원 · 1인·월 기준. 지난 달 정산은 바뀌지 않습니다.
        </p>
      </AdminWorkDialog>
    </div>
  );
}

// SCR-UI-15-M9
export function SalesPartNameDialog({
  part,
  reload,
  onCancel,
  onSaved,
  onDirtyChange,
}: DialogProps) {
  const [name, setName] = useState(part.bsn_part_nm);
  const [validation, setValidation] = useState<ApiFailure | null>(null);
  const write = useAdminWrite(reload);
  const error = validation ?? write.error;
  return (
    <div data-testid="sales-part-name-dialog" data-screen-id="SCR-UI-15-M9">
      <AdminWorkDialog
        title="파트명 수정"
        onCancel={onCancel}
        onSave={() => {
          if (!name.trim() || name.length > 30) {
            setValidation(
              new ApiFailure(
                "파트명을 입력해 주세요.",
                400,
                null,
                "bsn_part_nm",
              ),
            );
            return;
          }
          if (name === part.bsn_part_nm) {
            onCancel();
            return;
          }
          void write.save(
            () => updateSalesPartName(part.bsn_part_id, name),
            () => onSaved("파트명을 바꿨습니다."),
          );
        }}
        dirty={name !== part.bsn_part_nm}
        pending={write.pending}
        error={error}
        onRetry={() => {
          setValidation(null);
          void write.recover();
        }}
        blocked={write.uncertain}
        onDirtyChange={onDirtyChange}
      >
        <TextInput
          label="파트명"
          name="bsn_part_nm"
          required
          maxLength={30}
          value={name}
          onValueChange={(value) => {
            setValidation(null);
            write.clear();
            setName(value);
          }}
          error={error?.field === "bsn_part_nm" ? error.message : null}
          disabled={write.pending}
        />
        <p className={styles.help}>
          소속 인력·프로젝트·사업계획의 파트명도 함께 바뀝니다.
        </p>
      </AdminWorkDialog>
    </div>
  );
}

export function SalesPartActions({
  part,
  onUpdated,
  onListRefresh,
  onDirtyChange,
  readOnly = false,
}: {
  part: SalesPartDetail | null;
  onUpdated: (part: SalesPartDetail) => void;
  onListRefresh?: () => Promise<unknown>;
  onDirtyChange?: (dirty: boolean) => void;
  readOnly?: boolean;
}) {
  const [dialog, setDialog] = useState<
    "standard" | "name" | "disable" | "blocked" | null
  >(null);
  const [menu, setMenu] = useState(false);
  const [message, setMessage] = useState("");
  const refresh = async () => {
    if (!part) return;
    onUpdated(await readSalesPart(part.bsn_part_id));
    await onListRefresh?.();
  };
  const write = useAdminWrite(refresh);
  if (!part)
    return (
      <section data-testid="sales-part-actions">
        영업 파트를 선택하세요.
      </section>
    );
  const close = () => setDialog(null);
  const saved = (text: string) => {
    close();
    setMessage(text);
  };
  const blocked = dialog === "blocked" || write.error?.code === "PART_IN_USE";
  return (
    <section
      data-testid="sales-part-actions"
      data-screen-id="SCR-UI-15"
      className={styles.region}
    >
      {part.hq_yn === "Y" && <Badge tone="locked">본사</Badge>}
      {!readOnly && part.hq_yn !== "Y" && (
        <div
          className={styles.writeControls}
          data-testid="sales-part-write-controls"
        >
          <Can perm="UI-15.update">
            <Button
              data-testid="sales-part-standard-open"
              onClick={() => {
                write.clear();
                setDialog("standard");
              }}
            >
              기준 변경
            </Button>
          </Can>
          <Button
            data-testid="sales-part-more"
            aria-label="영업 파트 더보기"
            aria-expanded={menu}
            onClick={() => setMenu(!menu)}
          >
            ⋯
          </Button>
          {menu && (
            <div className={styles.menu} data-testid="sales-part-menu">
              <Can perm="UI-15.update">
                <Button
                  data-testid="sales-part-name-open"
                  onClick={() => {
                    setMenu(false);
                    write.clear();
                    setDialog("name");
                  }}
                >
                  파트명 수정
                </Button>
              </Can>
              <Can perm="UI-15.delete">
                {part.use_yn === "Y" ? (
                  <Button
                    data-testid="sales-part-disable-open"
                    onClick={() => {
                      setMenu(false);
                      write.clear();
                      setDialog(
                        part.peopleCount || part.projectCount
                          ? "blocked"
                          : "disable",
                      );
                    }}
                  >
                    사용 중지
                  </Button>
                ) : (
                  <Button
                    data-testid="sales-part-enable"
                    pending={write.pending}
                    disabled={write.uncertain}
                    onClick={() => {
                      void write.save(
                        () => saveSalesPartEnable(part.bsn_part_id),
                        () => {
                          setMenu(false);
                          setMessage(`${part.bsn_part_nm}을 다시 사용합니다.`);
                        },
                      );
                    }}
                  >
                    다시 사용
                  </Button>
                )}
              </Can>
            </div>
          )}
        </div>
      )}
      {dialog === "standard" && (
        <SalesPartStandardDialog
          key={part.bsn_part_id}
          part={part}
          reload={refresh}
          onCancel={close}
          onSaved={saved}
          onDirtyChange={onDirtyChange}
        />
      )}
      {dialog === "name" && (
        <SalesPartNameDialog
          key={part.bsn_part_id}
          part={part}
          reload={refresh}
          onCancel={close}
          onSaved={saved}
          onDirtyChange={onDirtyChange}
        />
      )}
      {(dialog === "disable" || dialog === "blocked") && (
        <Modal
          title={
            blocked
              ? "사용을 중지할 수 없습니다"
              : `${part.bsn_part_nm}의 사용을 중지할까요?`
          }
          onCancel={() => {
            if (!write.pending) close();
          }}
          confirmation
        >
          <div
            data-testid="sales-part-disable-dialog"
            data-screen-id={blocked ? "SCR-UI-15-M12" : "SCR-UI-15-M10"}
          >
            {blocked ? (
              <>
                <p>
                  {write.error?.code === "PART_IN_USE"
                    ? write.error.message
                    : `${part.bsn_part_nm}에 소속 인력 ${part.peopleCount}명, 진행 프로젝트 ${part.projectCount}건이 있습니다. 먼저 다른 파트로 옮겨 주세요.`}
                </p>
                <Button
                  data-testid="sales-part-blocked-close"
                  onClick={() => {
                    write.clear();
                    close();
                  }}
                >
                  확인
                </Button>
              </>
            ) : (
              <>
                <p>중지하면 새 인력·프로젝트에 고를 수 없습니다.</p>
                <ErrorBanner
                  error={write.error}
                  pending={write.pending}
                  onRetry={() => {
                    void write.recover();
                  }}
                />
                <div className={styles.footer}>
                  <Button
                    data-testid="sales-part-disable-cancel"
                    disabled={write.pending}
                    onClick={close}
                  >
                    취소
                  </Button>
                  <Button
                    data-testid="sales-part-disable-confirm"
                    className={styles.destructive}
                    pending={write.pending}
                    disabled={write.uncertain}
                    onClick={() => {
                      void write.save(
                        () => saveSalesPartDisable(part.bsn_part_id),
                        () => saved("사용을 중지했습니다."),
                      );
                    }}
                  >
                    사용 중지
                  </Button>
                </div>
              </>
            )}
          </div>
        </Modal>
      )}
      {!dialog && (
        <ErrorBanner
          error={write.error}
          pending={write.pending}
          onRetry={() => {
            void write.recover();
          }}
        />
      )}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
    </section>
  );
}
