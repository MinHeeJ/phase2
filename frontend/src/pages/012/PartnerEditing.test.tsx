import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PartnerDrawer } from "../partners/PartnerDrawer";
import { emptyPartner } from "../partners/PartnerForm";
import type { PartnerDetail } from "../partners/client";

const customer: PartnerDetail = {
  ...emptyPartner,
  ptnr_id: "1",
  ptnr_no: "C-001",
  ptnr_se_cd: "CUST",
  ptnr_nm: "가 거래처",
  biz_reg_no: "123-45-67890",
  txinv_email_addr: "tax@company.co.kr",
  use_yn: "Y",
};
const vendor: PartnerDetail = {
  ...customer,
  ptnr_se_cd: "VEND",
  txinv_email_addr: null,
  bank_cd: "KB",
  acnt_no: "123-456-7890",
  dpstr_nm: "예금주",
};
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
let row: PartnerDetail;
let fetcher: ReturnType<typeof vi.fn>;
const closed = vi.fn();
const saved = vi.fn().mockResolvedValue(undefined);
beforeEach(() => {
  vi.clearAllMocks();
  row = { ...customer };
  fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.startsWith("/api/codes/"))
      return response(
        url.endsWith("PARTNER_KIND")
          ? [
              { code: "CUST", name: "고객사" },
              { code: "VEND", name: "협력사" },
              { code: "BOTH", name: "둘 다" },
            ]
          : url.endsWith("BANK")
            ? [
                { code: "KB", name: "국민은행" },
                { code: "SHINHAN", name: "신한은행" },
              ]
            : [],
      );
    if (url.endsWith("/history")) return response([]);
    if (url === "/api/partners/1" && init?.method === "PUT") {
      row = { ...row, ...JSON.parse(init.body as string) };
      return response(row);
    }
    if (url === "/api/partners/1") return response(row);
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
const writes = () => fetcher.mock.calls.filter((c) => c[1]?.method === "PUT");
const change = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
async function edit(initial = customer) {
  row = { ...initial };
  render(
    <PartnerDrawer
      id={row.ptnr_id}
      onClose={closed}
      onSaved={saved}
      onStateAction={() => {}}
    />,
  );
  await screen.findByText("C-001");
  fireEvent.click(screen.getByRole("button", { name: "수정" }));
  await screen.findByRole("option", { name: "둘 다" });
}
const save = () =>
  fireEvent.click(screen.getByRole("button", { name: "저장" }));

describe("SCR-UI-15 partner editing", () => {
  it("edits in the same drawer, preserves readonly identity and requeries after save", async () => {
    await edit();
    expect(screen.getAllByRole("dialog")).toHaveLength(1);
    expect(screen.getByText("수정 중 · 123-45-67890")).toBeVisible();
    expect(screen.getByLabelText("사업자번호")).toHaveAttribute("readonly");
    expect(
      screen.getByText(/잘못 넣었으면 사용 중지 후 새로 등록하세요/),
    ).toBeVisible();
    change("상호", "변경한 상호");
    save();
    await waitFor(() => expect(saved).toHaveBeenCalledWith("저장했습니다."));
    expect(screen.queryByLabelText("상호")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "변경한 상호" })).toBeVisible();
    expect(writes()).toHaveLength(1);
    const body = JSON.parse(writes()[0][1].body as string);
    for (const key of ["biz_reg_no", "ptnr_id", "ptnr_no", "use_yn", "upld_id"])
      expect(body).not.toHaveProperty(key);
    const updateIndex = fetcher.mock.calls.findIndex(
      (c) => c[1]?.method === "PUT",
    );
    expect(
      fetcher.mock.calls
        .slice(updateIndex + 1)
        .some((c) => c[0] === "/api/partners/1"),
    ).toBe(true);
  });
  it("no-change save returns to viewing without a request", async () => {
    await edit();
    save();
    expect(saved).toHaveBeenCalledWith("바뀐 내용이 없습니다.");
    expect(screen.queryByLabelText("상호")).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });
  it("cancel discards draft and returns to viewing without saving", async () => {
    await edit();
    change("상호", "버릴 이름");
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(screen.queryByLabelText("상호")).not.toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: customer.ptnr_nm }),
    ).toBeVisible();
    expect(writes()).toHaveLength(0);
  });
  it.each([
    ["상호", "", "상호를 입력해 주세요."],
    ["세금계산서 이메일", "", "고객사는 세금계산서 이메일이 꼭 필요합니다."],
    [
      "세금계산서 이메일",
      "invalid",
      "이메일 형식이 맞지 않습니다. (예: tax@company.co.kr)",
    ],
  ])(
    "revalidates %s and keeps the invalid draft",
    async (label, value, message) => {
      await edit();
      change(label, value);
      save();
      expect(await screen.findByText(message)).toBeVisible();
      expect(screen.getByLabelText(label)).toHaveFocus();
      expect(screen.getByLabelText(label)).toHaveValue(value);
      expect(writes()).toHaveLength(0);
    },
  );
  it.each([
    ["은행", "", "은행을 골라 주세요."],
    ["계좌번호", "12A", "계좌번호를 숫자와 하이픈(-)으로 입력해 주세요."],
    ["예금주", "", "예금주를 입력해 주세요."],
  ])(
    "revalidates vendor %s without opening confirmation",
    async (label, value, message) => {
      await edit(vendor);
      change(label, value);
      save();
      expect(await screen.findByText(message)).toBeVisible();
      expect(screen.getByLabelText(label)).toHaveFocus();
      expect(screen.getAllByRole("dialog")).toHaveLength(1);
      expect(writes()).toHaveLength(0);
    },
  );
  it.each([
    ["은행", "SHINHAN"],
    ["계좌번호", "987-654-3210"],
    ["예금주", "새 예금주"],
  ])(
    "requires approval for existing account %s and preserves input on cancel",
    async (label, value) => {
      await edit(vendor);
      change(label, value);
      save();
      const dialog = screen.getByRole("dialog", {
        name: "지급 계좌를 바꿀까요?",
      });
      expect(within(dialog).getByText(/…(3210|7890)/)).toBeVisible();
      expect(writes()).toHaveLength(0);
      fireEvent.click(within(dialog).getByRole("button", { name: "취소" }));
      expect(screen.getByLabelText(label)).toHaveValue(value);
      expect(writes()).toHaveLength(0);
      save();
      fireEvent.click(screen.getByRole("button", { name: "바꾸기" }));
      await waitFor(() => expect(saved).toHaveBeenCalledWith("저장했습니다."));
      expect(writes()).toHaveLength(1);
    },
  );
  it("adds the first account without confirmation and hides unrelated sections", async () => {
    await edit();
    change("구분", "VEND");
    expect(
      screen.queryByLabelText("세금계산서 이메일"),
    ).not.toBeInTheDocument();
    change("은행", "KB");
    change("계좌번호", "123-456-7890");
    change("예금주", "예금주");
    save();
    await waitFor(() => expect(saved).toHaveBeenCalledWith("저장했습니다."));
    expect(writes()).toHaveLength(1);
    expect(
      JSON.parse(writes()[0][1].body as string).txinv_email_addr,
    ).toBeNull();
  });
  it("maps server field errors and keeps draft in edit mode", async () => {
    const original = fetcher.getMockImplementation()!;
    fetcher.mockImplementation(async (url: string, init?: RequestInit) =>
      init?.method === "PUT"
        ? new Response(
            JSON.stringify({
              success: false,
              meta: {},
              code: "INVALID_INPUT",
              field: "ptnr_nm",
              message: "입력 오류",
              requestId: "server-request",
            }),
            { status: 400 },
          )
        : original(url, init),
    );
    await edit();
    change("상호", "작성한 상호");
    save();
    expect(
      await screen.findByText("입력 오류", { selector: "span" }),
    ).toBeVisible();
    expect(screen.getByLabelText("상호")).toHaveValue("작성한 상호");
    expect(screen.getByLabelText("상호")).toHaveFocus();
    expect(saved).not.toHaveBeenCalled();
  });
  it.each(["button", "backdrop", "escape"])(
    "protects dirty closing by %s",
    async (trigger) => {
      await edit();
      change("상호", "보존할 이름");
      if (trigger === "button")
        fireEvent.click(screen.getByLabelText("상세 닫기"));
      else if (trigger === "backdrop")
        fireEvent.click(screen.getByTestId("partner-drawer-backdrop"));
      else fireEvent.keyDown(window, { key: "Escape" });
      const confirm = screen.getByRole("dialog", {
        name: "저장하지 않은 내용이 있습니다",
      });
      expect(
        within(confirm).getByText("창을 닫으면 고친 내용이 사라집니다."),
      ).toBeVisible();
      fireEvent.keyDown(confirm, { key: "Escape" });
      await waitFor(() =>
        expect(
          screen.queryByRole("dialog", {
            name: "저장하지 않은 내용이 있습니다",
          }),
        ).not.toBeInTheDocument(),
      );
      expect(screen.getByLabelText("상호")).toHaveValue("보존할 이름");
      expect(closed).not.toHaveBeenCalled();
      fireEvent.click(screen.getByLabelText("상세 닫기"));
      fireEvent.click(screen.getByRole("button", { name: /^닫기$/ }));
      expect(closed).toHaveBeenCalledOnce();
      expect(writes()).toHaveLength(0);
    },
  );
  it("Escape dismisses account confirmation without opening a dirty-close confirmation", async () => {
    await edit(vendor);
    change("예금주", "변경 예금주");
    save();
    fireEvent.keyDown(
      screen.getByRole("dialog", { name: "지급 계좌를 바꿀까요?" }),
      { key: "Escape" },
    );
    expect(
      screen.queryByRole("dialog", { name: "저장하지 않은 내용이 있습니다" }),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("예금주")).toHaveValue("변경 예금주");
    expect(closed).not.toHaveBeenCalled();
    expect(writes()).toHaveLength(0);
  });
});
