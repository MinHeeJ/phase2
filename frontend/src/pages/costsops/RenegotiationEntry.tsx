import { useState } from "react";
import { Button } from "../../shared/Controls";
import UI32Screen from "./UI32Screen";

export function RenegotiationEntry({ projectId }: { projectId?: number }) {
  const [open, setOpen] = useState(false);
  return open ? (
    <UI32Screen projectId={projectId} onClose={() => setOpen(false)} />
  ) : (
    <Button data-testid="open-renegotiations" onClick={() => setOpen(true)}>
      단가 재협상 대상
    </Button>
  );
}
