import { useCallback, useRef, useState } from "react";
import { Route, Routes } from "react-router-dom";
import UI27Screen from "../pages/admin/UI27Screen";
import PartnersScreen from "../pages/partners/UI15Screen";
import SalesPartsScreen from "../pages/salesparts/UI15Screen";
import {
  PartnerActivityTabs,
  PartnerDisableDialog,
  PartnerExportButton,
  partnerFailure,
} from "../pages/partnersops/PartnerActions";
import {
  getPartner,
  enablePartner,
  type PartnerDetail,
} from "../pages/partners/client";
import { SalesPartActions } from "../pages/salespartsops/SalesPartActions";
import { SalesPartDownload } from "../pages/salespartsops/SalesPartDownload";
import { UploadButton } from "../pages/uploadsops/UploadButton";
import { Button } from "../shared/Controls";
import { ErrorBanner } from "../shared/ErrorBanner";
import type { ApiFailure } from "../shared/api";
import { useSharedMobile } from "../pages/shared/PlaceholderScreen";
import {
  AppShell,
  type BusinessScreenBindings,
} from "../pages/shared/AppShell";

function CommonCodesScreen({
  onDirtyChange,
  name,
  groupName,
}: BusinessScreenBindings) {
  const notify = useRef(onDirtyChange);
  notify.current = onDirtyChange;
  const reportDirty = useCallback(
    (dirty: boolean) => notify.current(dirty),
    [],
  );
  return (
    <UI27Screen onDirtyChange={reportDirty} name={name} groupName={groupName} />
  );
}

function PartnerStatusActions({
  row,
  onChanged,
}: {
  row: PartnerDetail;
  onChanged: (row: PartnerDetail) => void;
}) {
  const [disable, setDisable] = useState(false),
    [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiFailure | null>(null);
  const mobile = useSharedMobile();
  const reload = async () => onChanged(await getPartner(row.ptnr_id));
  if (mobile) return null;
  return (
    <>
      <Button
        data-testid="partner-status-action"
        pending={pending}
        onClick={() => {
          if (row.use_yn === "Y") setDisable(true);
          else {
            setPending(true);
            setError(null);
            void enablePartner(row.ptnr_id)
              .then(reload)
              .catch((error) => setError(partnerFailure(error)))
              .finally(() => setPending(false));
          }
        }}
      >
        {row.use_yn === "Y" ? "사용 중지" : "다시 사용"}
      </Button>
      <ErrorBanner
        error={error}
        onRetry={() => {
          void reload()
            .then(() => setError(null))
            .catch((error) => setError(partnerFailure(error)));
        }}
      />
      {disable && (
        <PartnerDisableDialog
          rows={[row]}
          onCancel={() => setDisable(false)}
          onChanged={() => {
            void reload().catch((error) => setError(partnerFailure(error)));
          }}
        />
      )}
    </>
  );
}

function PartnersAndSalesPartsScreen({
  onDirtyChange,
  ...labels
}: BusinessScreenBindings) {
  const notify = useRef(onDirtyChange);
  notify.current = onDirtyChange;
  const reportDirty = useCallback(
    (dirty: boolean) => notify.current(dirty),
    [],
  );
  const mobile = useSharedMobile();
  return (
    <PartnersScreen
      {...labels}
      onDirtyChange={reportDirty}
      renderUploads={(onApplied) =>
        !mobile && <UploadButton form="B.7" onApplied={onApplied} />
      }
      renderExport={(filters, columns, ids, total) =>
        !mobile && (
          <PartnerExportButton
            resource="partners"
            filters={{ kind: filters.kind, useYn: filters.useYn, q: filters.q }}
            columns={columns}
            ids={ids}
            total={total}
          />
        )
      }
      renderDisable={(rows, onCancel, onChanged) =>
        !mobile && (
          <PartnerDisableDialog
            rows={rows}
            onCancel={onCancel}
            onChanged={onChanged}
          />
        )
      }
      renderPartnerActions={(row, onChanged) => (
        <PartnerStatusActions row={row} onChanged={onChanged} />
      )}
      renderActivity={(id, tab) => (
        <PartnerActivityTabs
          key={`${id}-${tab}`}
          id={id}
          initial={tab}
          embedded
        />
      )}
      salesParts={(onPartners) => (
        <SalesPartsScreen
          onPartners={onPartners}
          onDirtyChange={reportDirty}
          renderActions={(detail, onDetailChange, onChanged, onActionDirty) => (
            <SalesPartActions
              detail={detail}
              onDetailChange={onDetailChange}
              onChanged={onChanged}
              onDirtyChange={onActionDirty}
            />
          )}
          renderDownload={(columns, selectedIds, total) =>
            !mobile && (
              <SalesPartDownload
                columns={columns}
                selectedIds={selectedIds}
                total={total}
              />
            )
          }
        />
      )}
    />
  );
}

export function AppRouter() {
  return (
    <Routes>
      <Route
        path="/*"
        element={
          <AppShell
            renderBusiness={(id, bindings) =>
              id === "UI-27" ? (
                <CommonCodesScreen {...bindings} />
              ) : id === "UI-15" ? (
                <PartnersAndSalesPartsScreen {...bindings} />
              ) : null
            }
          />
        }
      />
    </Routes>
  );
}

export default AppRouter;
