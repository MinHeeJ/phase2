import { useState } from "react";
import UI10Screen, { type EntriesScreenProps } from "../entries/UI10Screen";
import { downloadUploadFile, uploadFailure } from "../uploadsops/client";
import { ErrorBanner } from "../../shared/ErrorBanner";
import type { ApiFailure } from "../../shared/api";
import { FinanceEntriesUploadDialog } from "./FinanceEntriesUploadDialog";

export default function FinanceEntriesScreen(props: EntriesScreenProps) {
  const [uploading, setUploading] = useState(false),
    [revision, setRevision] = useState(0);
  const [error, setError] = useState<ApiFailure | null>(null);
  const template = () => {
    setError(null);
    void downloadUploadFile(
      "/api/uploads/templates/B.2",
      "재무항목_업로드양식.xlsx",
    ).catch((e) => setError(uploadFailure(e)));
  };
  return (
    <section data-testid="finance-entries-screen">
      <ErrorBanner error={error} />
      <UI10Screen
        {...props}
        refreshVersion={(props.refreshVersion ?? 0) + revision}
        onTemplate={props.onTemplate ?? template}
        onUpload={props.onUpload ?? (() => setUploading(true))}
      />
      {uploading && (
        <FinanceEntriesUploadDialog
          onClose={() => setUploading(false)}
          onApplied={() => setRevision((r) => r + 1)}
        />
      )}
    </section>
  );
}
