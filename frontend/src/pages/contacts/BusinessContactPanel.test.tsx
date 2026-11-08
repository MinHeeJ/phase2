import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { BusinessContactPanel } from "./BusinessContactPanel";
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetcher = vi.fn(
    async () =>
      new Response(
        JSON.stringify({
          success: true,
          meta: {},
          data: {
            phone: "permitted-phone",
            email: "permitted@example.invalid",
          },
        }),
        { headers: { "Content-Type": "application/json" } },
      ),
  );
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => vi.unstubAllGlobals());
it("developer contact requires selected purpose and clears original on close", async () => {
  render(<BusinessContactPanel kind="developers" id={67} />);
  expect(screen.getByTestId("developers-contact-view")).toBeDisabled();
  fireEvent.change(screen.getByLabelText("열람 목적"), {
    target: { value: "STAFF_OPERATION" },
  });
  fireEvent.click(screen.getByRole("button", { name: "원문 보기" }));
  expect(await screen.findByText("전화: permitted-phone")).toBeInTheDocument();
  expect(fetcher.mock.calls[0][0]).toBe(
    "/api/developers/67/contact?purpose=STAFF_OPERATION",
  );
  fireEvent.click(screen.getByRole("button", { name: "원문 닫기" }));
  expect(screen.queryByText("전화: permitted-phone")).not.toBeInTheDocument();
});
it("partner financial purpose uses selected partner API, independently of developer flow", async () => {
  render(<BusinessContactPanel kind="partners" id="79" />);
  fireEvent.change(screen.getByLabelText("열람 목적"), {
    target: { value: "BILLING" },
  });
  fireEvent.click(screen.getByRole("button", { name: "원문 보기" }));
  await screen.findByText("전화: permitted-phone");
  expect(fetcher.mock.calls[0][0]).toBe(
    "/api/partners/79/contact?purpose=BILLING",
  );
  fireEvent.change(screen.getByLabelText("열람 목적"), {
    target: { value: "PAYMENT" },
  });
  expect(
    screen.queryByTestId("partners-contact-result"),
  ).not.toBeInTheDocument();
});
it("403 has no original and permission change clears existing contact", async () => {
  render(<BusinessContactPanel kind="partners" id="79" />);
  fireEvent.change(screen.getByLabelText("열람 목적"), {
    target: { value: "CONTRACT_SALES" },
  });
  fireEvent.click(screen.getByRole("button", { name: "원문 보기" }));
  await screen.findByText("전화: permitted-phone");
  window.dispatchEvent(new Event("permissions-changed"));
  await waitFor(() =>
    expect(
      screen.queryByTestId("partners-contact-result"),
    ).not.toBeInTheDocument(),
  );
  fetcher.mockImplementation(
    async () =>
      new Response(
        JSON.stringify({
          success: false,
          message: "접근 권한이 없습니다.",
          code: "FORBIDDEN",
          meta: {},
        }),
        { status: 403 },
      ),
  );
  fireEvent.click(screen.getByRole("button", { name: "원문 보기" }));
  await waitFor(() =>
    expect(screen.queryByText("전화: permitted-phone")).not.toBeInTheDocument(),
  );
});
