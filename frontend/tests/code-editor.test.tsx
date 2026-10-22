import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { CodeEditor } from "../src/features/common-codes/CodeEditor";
import type { Group } from "../src/services/api";
const group: Group = {
  cd_grp_id: "CONTRACT_TYPE",
  cd_grp_nm: "계약유형",
  cd_grp_se_cd: "SYSTEM",
  sort_seq: 1,
  codeCount: 1,
  attr1_nm: null,
  attr2_nm: null,
  attr3_nm: null,
  use_yn: "Y",
};
afterEach(() => vi.unstubAllGlobals());
it("SYSTEM은 코드 불변·이름과 순서만·사용 체크 비활성·추가 없음", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            success: true,
            meta: {},
            data: {
              items: [
                {
                  dtl_cd: "TK",
                  cd_nm: "턴키",
                  sort_seq: 1,
                  use_yn: "Y",
                  attr1_val: null,
                  attr2_val: null,
                  attr3_val: null,
                  aply_bgn_dt: null,
                },
              ],
              total: 1,
              page: 1,
              size: 100,
            },
          }),
        ),
    ),
  );
  render(
    <CodeEditor
      groups={[group]}
      guard={(action) => action()}
      setDirty={vi.fn()}
      toast={vi.fn()}
    />,
  );
  expect(await screen.findByLabelText("코드 1")).toHaveAttribute("readonly");
  expect(screen.getByLabelText("이름 1")).not.toHaveAttribute("readonly");
  expect(screen.getByLabelText("사용 1")).toBeDisabled();
  expect(screen.getByLabelText("적용 시작일 1")).toHaveAttribute("readonly");
  expect(
    screen.queryByRole("button", { name: "코드 추가" }),
  ).not.toBeInTheDocument();
});
it("저장 실패와 다시 조회에서도 수정값 보존, 오류는 alert", async () => {
  const biz = { ...group, cd_grp_se_cd: "BIZ" as const };
  const user = userEvent.setup();
  vi.stubGlobal(
    "fetch",
    vi.fn(async (_path: string, init?: RequestInit) =>
      init?.method === "PUT"
        ? new Response(
            JSON.stringify({
              success: false,
              code: "SERVER_ERROR",
              message: "저장 오류",
              field: "cd_nm",
              requestId: "test-request-id",
              meta: {},
            }),
            { status: 500 },
          )
        : new Response(
            JSON.stringify({
              success: true,
              meta: {},
              data: {
                items: [
                  {
                    dtl_cd: "TK",
                    cd_nm: "원래 이름",
                    sort_seq: 1,
                    use_yn: "Y",
                    attr1_val: null,
                    attr2_val: null,
                    attr3_val: null,
                    aply_bgn_dt: null,
                  },
                ],
                total: 1,
                page: 1,
                size: 100,
              },
            }),
          ),
    ),
  );
  render(
    <CodeEditor
      groups={[biz]}
      guard={(action) => action()}
      setDirty={vi.fn()}
      toast={vi.fn()}
    />,
  );
  const name = await screen.findByLabelText("이름 1");
  await user.clear(name);
  await user.type(name, "유지할 이름");
  await user.click(screen.getByRole("button", { name: "저장" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("test-request-id");
  expect(screen.getByLabelText("이름 1")).toHaveValue("유지할 이름");
  await user.click(screen.getByRole("button", { name: "다시 시도" }));
  await waitFor(() =>
    expect(screen.getByLabelText("이름 1")).toHaveValue("유지할 이름"),
  );
});
