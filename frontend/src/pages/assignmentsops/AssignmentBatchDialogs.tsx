import { useCallback, useState } from "react";
import { Badge, Button, Modal, Skeleton } from "../../shared/Controls";
import { SelectInput } from "../../shared/Inputs";
import { ErrorBanner } from "../../shared/ErrorBanner";
import { Can } from "../../shared/Can";
import { useCodes } from "../shared/useCodes";
import { useAdminRead, useAdminWrite } from "../admin/useAdminResource";
import * as client from "./client";
import type { AssignmentSelection, BatchResult } from "./client";
import styles from "./AssignmentDialogs.module.css";

export function AssignmentBatchResult({ result }: { result: BatchResult }) {
  return (
    <ul data-testid="assignment-batch-result" className={styles.results}>
      {result.items.map((item) => (
        <li
          key={item.asgn_id}
          data-testid={`assignment-result-${item.asgn_id}`}
        >
          <Badge
            tone={
              item.status === "APPLIED"
                ? "success"
                : item.status === "EXCLUDED"
                  ? "danger"
                  : "neutral"
            }
          >
            {item.status === "APPLIED"
              ? "처리됨"
              : item.status === "EXCLUDED"
                ? "제외"
                : "건너뜀"}
          </Badge>{" "}
          {item.message}
        </li>
      ))}
    </ul>
  );
}
export function AssignmentExtChanceDialog({
  rows,
  onCancel,
  onSaved,
  reload,
}: {
  rows: AssignmentSelection[];
  onCancel: () => void;
  onSaved: (result: BatchResult) => void;
  reload: () => Promise<unknown>;
}) {
  const codes = useCodes("EXT_POSSIBILITY");
  const [value, setValue] = useState<string | undefined>();
  const [invalid, setInvalid] = useState(false);
  const write = useAdminWrite(reload);
  return (
    <Modal
      title="연장 가능성 변경"
      onCancel={() => {
        if (!write.pending) onCancel();
      }}
    >
      <section
        data-screen-id="SCR-UI-12-M5"
        data-testid="assignment-ext-chance-dialog"
        className={styles.form}
      >
        <p>{rows.length}건 선택</p>
        <ErrorBanner error={codes.error} onRetry={codes.retry} />
        <ErrorBanner
          error={write.error}
          pending={write.pending}
          onRetry={() => void write.recover()}
        />
        <SelectInput
          name="value"
          label="연장 가능성"
          value={value ?? ""}
          disabled={
            write.pending || write.uncertain || codes.loading || !!codes.error
          }
          error={invalid ? "연장 가능성을 선택하세요." : null}
          options={[
            ...codes.codes
              .filter((c) => ["H", "M", "L"].includes(c.code))
              .map((c) => ({ value: c.code, label: c.name })),
            { value: "UNDECIDED", label: "미정" },
          ]}
          onValueChange={(v) => {
            setValue(v);
            setInvalid(false);
          }}
        />
        <div className={styles.actions}>
          <Button
            data-testid="assignment-chance-cancel"
            disabled={write.pending}
            onClick={onCancel}
          >
            취소
          </Button>
          <Can perm="UI-12.update">
            <Button
              data-testid="assignment-chance-save"
              variant="primary"
              pending={write.pending}
              disabled={
                rows.length === 0 ||
                write.uncertain ||
                codes.loading ||
                !!codes.error
              }
              onClick={() => {
                if (!value) {
                  setInvalid(true);
                  return;
                }
                let result: BatchResult;
                void write.save(
                  async () => {
                    result = await client.saveAssignmentExtChance(
                      rows,
                      value === "UNDECIDED" ? null : value,
                    );
                  },
                  () => onSaved(result),
                );
              }}
            >
              변경
            </Button>
          </Can>
        </div>
      </section>
    </Modal>
  );
}
export function AssignmentCancellationDialog({
  rows,
  onCancel,
  onSaved,
  reload,
}: {
  rows: AssignmentSelection[];
  onCancel: () => void;
  onSaved: (result: BatchResult) => void;
  reload: () => Promise<unknown>;
}) {
  const ids = rows.map((r) => r.asgn_id).join(",");
  const eligibility = useAdminRead(
    useCallback(
      () =>
        Promise.all(
          ids
            .split(",")
            .filter(Boolean)
            .map((id) => client.getAssignment(Number(id))),
        ),
      [ids],
    ),
  );
  const write = useAdminWrite(reload);

  const details = eligibility.data ?? [];
  const blocked = (row: AssignmentSelection) =>
    row.asgn_stts_cd === "CANCELLED" ||
    row.asgn_stts_cd === "REPLACED" ||
    row.warnings.some((w) => w.code === "ASSIGNMENT_IN_USE");
  const eligible = details.filter((row) => !blocked(row));
  return (
    <Modal
      title={`${rows.length}건의 배정을 취소할까요?`}
      confirmation
      onCancel={() => {
        if (!write.pending) onCancel();
      }}
    >
      <section
        data-screen-id="SCR-UI-12-M6"
        data-testid="assignment-cancellation-dialog"
        className={styles.form}
      >
        <p className={styles.danger}>
          취소한 투입은 MM·원가·청구에서 빠집니다.
        </p>
        <p>마감된 MM이나 청구 내역이 있는 투입은 취소할 수 없습니다.</p>
        <ErrorBanner
          error={eligibility.error}
          onRetry={() => void eligibility.reload().catch(() => {})}
        />
        <ErrorBanner
          error={write.error}
          pending={write.pending}
          onRetry={() => void write.recover()}
        />
        {eligibility.loading ? (
          <Skeleton />
        ) : (
          <ul className={styles.results}>
            {details.map((row) => (
              <li
                key={row.asgn_id}
                data-testid={`assignment-cancel-review-${row.asgn_id}`}
              >
                <strong>
                  {row.asgn_no} · {row.dev_nm}
                </strong>{" "}
                {blocked(row) ? (
                  <span className={styles.danger}>
                    {row.warnings
                      .filter((w) => w.code === "ASSIGNMENT_IN_USE")
                      .map((w) => w.message)
                      .join(" · ") || "변경할 수 없는 투입 상태입니다."}
                  </span>
                ) : (
                  <span>취소 가능</span>
                )}
              </li>
            ))}
          </ul>
        )}

        <div className={styles.actions}>
          <Button
            data-testid="assignment-cancel-dismiss"
            disabled={write.pending}
            onClick={onCancel}
          >
            취소
          </Button>
          <Can perm="UI-12.delete">
            <Button
              data-testid="assignment-cancel-submit"
              className={styles.dangerButton}
              pending={write.pending}
              disabled={
                eligibility.loading ||
                !!eligibility.error ||
                eligible.length === 0 ||
                write.uncertain
              }
              onClick={() => {
                let result: BatchResult;
                void write.save(
                  async () => {
                    result = await client.saveAssignmentCancellation(rows);
                  },
                  () => onSaved(result),
                );
              }}
            >
              배정 취소
            </Button>
          </Can>
        </div>
      </section>
    </Modal>
  );
}
