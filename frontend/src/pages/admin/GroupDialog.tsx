import { useState } from "react";
import {
  Actions,
  Button,
  ErrorBanner,
  Form,
  Modal,
  TextInput,
} from "../../shared";
import { adminApi, type CodeGroupCreate } from "./adminApi";
import {
  adminFieldError,
  useAdminDiscard,
  useAdminMutation,
} from "./adminState";

export function GroupDialog({
  onClose,
  onSaved,
}: {
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const [body, setBody] = useState<CodeGroupCreate>({
    cd_grp_id: "",
    cd_grp_nm: "",
    attr1_nm: null,
    attr2_nm: null,
    attr3_nm: null,
  });
  const discard = useAdminDiscard(Object.values(body).some(Boolean));
  const mutation = useAdminMutation(async () => {
    await onSaved();
    onClose();
  }, onSaved);
  function change(field: keyof CodeGroupCreate, value: string | null) {
    mutation.clear();
    setBody((current) => ({ ...current, [field]: value }));
  }
  return (
    <>
      <Modal
        title="그룹 추가"
        onClose={() => {
          if (!mutation.pending) discard.request(onClose);
        }}
      >
        {mutation.error && (
          <ErrorBanner
            error={mutation.error}
            onRetry={() => {
              void onSaved();
            }}
          />
        )}
        <Form
          onSubmit={() =>
            void mutation.run(() => adminApi.createCodeGroup(body))
          }
        >
          <TextInput
            label="그룹 코드"
            name="cd_grp_id"
            required
            maxLength={30}
            disabled={mutation.pending}
            value={body.cd_grp_id}
            error={
              adminFieldError(mutation.error, "cd_grp_id") ||
              (body.cd_grp_id && !/^[A-Z_]{1,30}$/.test(body.cd_grp_id)
                ? "영문 대문자와 _만 입력하세요."
                : undefined)
            }
            onChange={(v) => change("cd_grp_id", v)}
          />
          <TextInput
            label="그룹명"
            name="cd_grp_nm"
            required
            maxLength={100}
            disabled={mutation.pending}
            value={body.cd_grp_nm}
            error={adminFieldError(mutation.error, "cd_grp_nm")}
            onChange={(v) => change("cd_grp_nm", v)}
          />
          {([1, 2, 3] as const).map((n) => (
            <TextInput
              key={n}
              label={`추가 값 이름${n}`}
              name={`attr${n}_nm`}
              maxLength={50}
              disabled={mutation.pending}
              value={body[`attr${n}_nm`] ?? ""}
              error={adminFieldError(mutation.error, `attr${n}_nm`)}
              onChange={(v) => change(`attr${n}_nm`, v || null)}
            />
          ))}
          <Actions>
            <Button
              disabled={mutation.pending}
              onClick={() => discard.request(onClose)}
            >
              취소
            </Button>
            <Button type="submit" variant="primary" pending={mutation.pending}>
              저장
            </Button>
          </Actions>
        </Form>
      </Modal>
      {discard.dialog}
    </>
  );
}
