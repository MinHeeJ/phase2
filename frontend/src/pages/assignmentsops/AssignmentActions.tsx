import { useState } from "react";
import { Badge, Button, SuccessToast } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import {
  AssignmentExtChanceDialog,
  AssignmentCancellationDialog,
  AssignmentBatchResult,
} from "./AssignmentBatchDialogs";
import { AssignmentReplacementDialog } from "./AssignmentReplacementDialog";
import { UI12ExportButton } from "./UI12ExportButton";
import type {
  AssignmentFilter,
  AssignmentSelection,
  BatchResult,
} from "./client";
import styles from "./AssignmentDialogs.module.css";

export function AssignmentActions({
  selected,
  assignment,
  filter,
  today,
  readonly = false,
  reload,
  onOpenAssignment,
  onDirty,
}: {
  selected: AssignmentSelection[];
  assignment?: AssignmentSelection;
  filter: AssignmentFilter;
  today: string;
  readonly?: boolean;
  reload: () => Promise<unknown>;
  onOpenAssignment: (id: number) => void;
  onDirty?: (dirty: boolean) => void;
}) {
  const [dialog, setDialog] = useState<"chance" | "cancel" | "replace" | null>(
    null,
  );
  const [result, setResult] = useState<BatchResult | null>(null);
  const [message, setMessage] = useState("");
  const oldYear = filter.year < Number(today.slice(0, 4));
  const locked = readonly || oldYear;
  const rows = assignment ? [assignment] : selected;
  const editable = !assignment || assignment.asgn_stts_cd === "ACTIVE";
  const saved = (result: BatchResult) => {
    setResult(result);
    setDialog(null);
  };
  return (
    <section
      data-testid="assignment-actions"
      data-screen-id="SCR-UI-12"
      className={styles.form}
    >
      <div className={styles.toolbar}>
        {oldYear && <Badge tone="locked">읽기 전용</Badge>}
        {!assignment && <span>{selected.length}건 선택</span>}
        {!locked && editable && (
          <>
            {!assignment && (
              <Can perm="UI-12.update">
                <Button
                  data-testid="assignment-open-chance"
                  disabled={selected.length === 0}
                  onClick={() => setDialog("chance")}
                >
                  연장 가능성 변경
                </Button>
              </Can>
            )}
            {assignment && (
              <Can perm="UI-12.update">
                <Button
                  data-testid="assignment-open-replace"
                  onClick={() => setDialog("replace")}
                >
                  교체
                </Button>
              </Can>
            )}
            <Can perm="UI-12.delete">
              <Button
                data-testid="assignment-open-cancel"
                disabled={rows.length === 0}
                onClick={() => setDialog("cancel")}
              >
                배정 취소
              </Button>
            </Can>
          </>
        )}
        {!assignment && <UI12ExportButton filter={filter} />}
      </div>
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
      {result && <AssignmentBatchResult result={result} />}
      {!locked && editable && dialog === "chance" && (
        <AssignmentExtChanceDialog
          rows={rows}
          reload={reload}
          onCancel={() => setDialog(null)}
          onSaved={(r) => {
            saved(r);
            setMessage("연장 가능성을 변경했습니다.");
          }}
        />
      )}
      {!locked && editable && dialog === "cancel" && (
        <AssignmentCancellationDialog
          rows={rows}
          reload={reload}
          onCancel={() => setDialog(null)}
          onSaved={(r) => {
            saved(r);
            setMessage("배정을 취소했습니다.");
          }}
        />
      )}
      {!locked && editable && dialog === "replace" && assignment && (
        <AssignmentReplacementDialog
          assignment={assignment}
          today={today}
          onDirty={onDirty}
          reload={reload}
          onCancel={() => setDialog(null)}
          onSaved={(r) => {
            setDialog(null);
            setMessage("교체를 저장했습니다.");
            onOpenAssignment(r.new_assignment.asgn_id);
          }}
        />
      )}
    </section>
  );
}
