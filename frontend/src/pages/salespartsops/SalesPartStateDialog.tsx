import { useEffect, useRef } from "react";
import { Button, Modal } from "../../shared/Controls";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { useAdminWrite } from "../admin/useAdminResource";
import type { SalesPartDetail } from "../salesparts/client";
import { saveSalesPartDisable, saveSalesPartEnable } from "./client";
import styles from "./SalesPartLifecycle.module.css";

export function SalesPartStateDialog({
  part,
  action,
  reload,
  onClose,
  onSaved,
}: {
  part: SalesPartDetail;
  action: "disable" | "enable";
  reload: () => Promise<unknown>;
  onClose: () => void;
  onSaved: (message: string) => void;
}) {
  const started = useRef(false),
    enabled = useRef(false),
    recovering = useRef(false);
  const successMessage =
    action === "disable"
      ? "사용을 중지했습니다."
      : `${part.bsn_part_nm}을 다시 사용합니다.`;
  const write = useAdminWrite(async () => {
    await reload();
    if (action === "enable" && enabled.current && recovering.current)
      onSaved(successMessage);
  });
  const blocked =
    action === "disable" &&
    (part.peopleCount > 0 ||
      part.projectCount > 0 ||
      write.error?.code === "PART_IN_USE");
  const run = () =>
    write.save(
      async () => {
        if (action === "disable") return saveSalesPartDisable(part.bsn_part_id);
        const result = await saveSalesPartEnable(part.bsn_part_id);
        enabled.current = true;
        return result;
      },
      () => onSaved(successMessage),
    );
  useEffect(() => {
    if (action === "enable" && !started.current) {
      started.current = true;
      void run();
    }
  }, [action]);
  return (
    <Modal
      title={
        blocked
          ? "사용을 중지할 수 없습니다"
          : action === "disable"
            ? `${part.bsn_part_nm}의 사용을 중지할까요?`
            : "다시 사용"
      }
      confirmation
      onCancel={() => {
        if (!write.pending) onClose();
      }}
    >
      <div
        data-screen-id={blocked ? "SCR-UI-15-M12" : "SCR-UI-15-M10"}
        data-testid="sales-part-state-dialog"
      >
        {blocked ? (
          <p>
            {write.error?.code === "PART_IN_USE"
              ? write.error.message
              : `${part.bsn_part_nm}에 소속 인력 ${part.peopleCount}명, 진행 프로젝트 ${part.projectCount}건이 있습니다. 먼저 다른 파트로 옮겨 주세요.`}
          </p>
        ) : (
          <p>
            {action === "disable"
              ? "중지하면 새 인력·프로젝트에 고를 수 없습니다."
              : "다시 사용하도록 변경 중입니다."}
          </p>
        )}
        <ErrorBanner
          error={write.error?.code === "PART_IN_USE" ? null : write.error}
          onRetry={async () => {
            recovering.current = true;
            try {
              await write.recover();
            } finally {
              recovering.current = false;
            }
          }}
          pending={write.pending}
        />
        <div className={styles.actions}>
          <Button
            data-testid="sales-part-state-cancel"
            disabled={write.pending}
            onClick={onClose}
          >
            {blocked ? "확인" : "취소"}
          </Button>
          {!blocked && action === "disable" && (
            <Button
              data-testid="sales-part-state-confirm"
              className={styles.danger}
              pending={write.pending}
              disabled={write.uncertain}
              onClick={() => {
                void run();
              }}
            >
              사용 중지
            </Button>
          )}
          {action === "enable" && write.error && (
            <Button
              data-testid="sales-part-enable-retry"
              disabled={write.pending || write.uncertain}
              onClick={() => {
                void run();
              }}
            >
              다시 사용
            </Button>
          )}
        </div>
      </div>
    </Modal>
  );
}
