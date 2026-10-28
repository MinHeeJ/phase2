import { Button, Modal } from "../../shared/Controls";
import {
  DispatchAttendanceUploadPanel,
  type AttendanceUploadProps,
} from "./DispatchAttendanceUploadPanel";

export default function UI13M2({
  onClose,
  ...props
}: AttendanceUploadProps & { onClose: () => void }) {
  return (
    <Modal title="파견 근태" onCancel={onClose}>
      <div data-testid="attendance-upload-dialog">
        <DispatchAttendanceUploadPanel {...props} />
        <Button data-testid="attendance-upload-close" onClick={onClose}>
          닫기
        </Button>
      </div>
    </Modal>
  );
}
