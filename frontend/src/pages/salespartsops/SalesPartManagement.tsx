import { useState } from "react";
import { SuccessToast } from "../../shared/Controls";
import { Can } from "../../shared/Can";
import SalesPartsTab, {
  type SalesPartAction,
} from "../salesparts/SalesPartsTab";
import type { SalesPartDetail } from "../salesparts/client";
import { ListExportButton } from "../partnersops/PartnerActions";
import { SalesPartEditDialog } from "./SalesPartEditDialog";
import { SalesPartStateDialog } from "./SalesPartStateDialog";
import { salesPartExportColumns } from "./client";
import styles from "./SalesPartLifecycle.module.css";

// SCR-UI-15: compose with the existing partner screen through its salesParts slot.
export default function SalesPartManagement({
  onDirtyChange,
}: {
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const [action, setAction] = useState<{
    mode: SalesPartAction;
    part: SalesPartDetail;
    refresh: () => Promise<unknown>;
  } | null>(null);
  const [message, setMessage] = useState("");
  const saved = (text: string) => {
    setAction(null);
    onDirtyChange?.(false);
    setMessage(text);
  };
  return (
    <>
      <SalesPartsTab
        onDirtyChange={onDirtyChange}
        onAction={(mode, part, refresh) => {
          if (part.hq_yn !== "Y") setAction({ mode, part, refresh });
        }}
        renderExport={(ids, total) => (
          <span className={styles.download}>
            <Can perm="UI-15.download">
              <ListExportButton
                resource="sales-parts"
                filter={{}}
                columns={salesPartExportColumns}
                selectedIds={ids}
                total={total}
              />
            </Can>
          </span>
        )}
      />
      {action &&
        (action.mode === "name" || action.mode === "standards" ? (
          <SalesPartEditDialog
            key={`${action.part.bsn_part_id}-${action.mode}`}
            mode={action.mode}
            part={action.part}
            reload={action.refresh}
            onCancel={() => setAction(null)}
            onSaved={saved}
            onDirtyChange={onDirtyChange}
          />
        ) : (
          <SalesPartStateDialog
            key={`${action.part.bsn_part_id}-${action.mode}`}
            action={action.mode}
            part={action.part}
            reload={action.refresh}
            onClose={() => setAction(null)}
            onSaved={saved}
          />
        ))}
      {message && (
        <SuccessToast message={message} onDismiss={() => setMessage("")} />
      )}
    </>
  );
}
