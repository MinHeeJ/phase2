import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { BusinessContact } from "./BusinessContact";

const response = (phone: string) =>
  new Response(
    JSON.stringify({
      success: true,
      meta: {},
      data: { phone, email: null, masked: false },
    }),
    { headers: { "Content-Type": "application/json" } },
  );
function deferred() {
  let resolve!: (value: Response) => void;
  const promise = new Promise<Response>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it.each(["sicost-session-expired", "sicost:permissions-changed"])(
  "%s cancels a pending contact request and ignores even a late successful response",
  async (event) => {
    const pending = deferred();
    const fetcher = vi.fn(
      (_url: string, _init?: RequestInit) => pending.promise,
    );
    vi.stubGlobal("fetch", fetcher);
    render(<BusinessContact kind="partners" id={42} />);
    fireEvent.click(screen.getByRole("button", { name: "원문 보기" }));
    expect(screen.getByRole("button", { name: "원문 보기" })).toBeDisabled();
    act(() => {
      window.dispatchEvent(new Event(event));
    });
    expect(fetcher.mock.calls[0][1]?.signal?.aborted).toBe(true);
    await act(async () => {
      pending.resolve(response("02-000-0042"));
    });
    expect(screen.queryByText(/02-000-0042/)).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "원문 보기" })).toBeEnabled();
  },
);

it("a purpose change keeps a newer response and never restores the earlier purpose's contact", async () => {
  const previous = deferred(),
    current = deferred();
  const fetcher = vi
    .fn((_url: string, _init?: RequestInit) => previous.promise)
    .mockImplementationOnce(() => previous.promise)
    .mockImplementationOnce(() => current.promise);
  vi.stubGlobal("fetch", fetcher);
  render(<BusinessContact kind="partners" id={42} />);
  fireEvent.click(screen.getByRole("button", { name: "원문 보기" }));
  fireEvent.change(screen.getByLabelText("업무 목적"), {
    target: { value: "BILLING_RECEIPT_PAYMENT" },
  });
  expect(fetcher.mock.calls[0][1]?.signal?.aborted).toBe(true);
  fireEvent.click(screen.getByRole("button", { name: "원문 보기" }));
  expect(fetcher.mock.calls[1][0]).toBe(
    "/api/partners/42/contact?purpose=BILLING_RECEIPT_PAYMENT",
  );
  await act(async () => {
    current.resolve(response("02-000-0043"));
  });
  expect(screen.getByText(/02-000-0043/)).toBeVisible();
  await act(async () => {
    previous.resolve(response("02-000-0042"));
  });
  expect(screen.queryByText(/02-000-0042/)).not.toBeInTheDocument();
  expect(screen.getByText(/02-000-0043/)).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "원문 닫기" }));
  expect(screen.queryByText(/02-000-0043/)).not.toBeInTheDocument();
});

it("changing the selected target aborts the old request and binds the next call to the new row", async () => {
  const previous = deferred();
  const fetcher = vi
    .fn((_url: string, _init?: RequestInit) => previous.promise)
    .mockImplementationOnce(() => previous.promise)
    .mockResolvedValueOnce(response("02-000-0043"));
  vi.stubGlobal("fetch", fetcher);
  const view = render(<BusinessContact kind="partners" id={42} />);
  fireEvent.click(screen.getByRole("button", { name: "원문 보기" }));
  view.rerender(<BusinessContact kind="partners" id={43} />);
  expect(fetcher.mock.calls[0][1]?.signal?.aborted).toBe(true);
  fireEvent.click(screen.getByRole("button", { name: "원문 보기" }));
  expect(await screen.findByText(/02-000-0043/)).toBeVisible();
  expect(fetcher.mock.calls[1][0]).toBe(
    "/api/partners/43/contact?purpose=CONTRACT_SALES",
  );
  await act(async () => {
    previous.resolve(response("02-000-0042"));
  });
  expect(screen.queryByText(/02-000-0042/)).not.toBeInTheDocument();
  expect(screen.getByText(/02-000-0043/)).toBeVisible();
});

it("closing the detail cancels the in-flight developer contact request", async () => {
  const pending = deferred();
  const fetcher = vi.fn((_url: string, _init?: RequestInit) => pending.promise);
  vi.stubGlobal("fetch", fetcher);
  const view = render(<BusinessContact kind="developers" id={31} />);
  fireEvent.click(screen.getByRole("button", { name: "원문 보기" }));
  view.unmount();
  expect(fetcher.mock.calls[0][1]?.signal?.aborted).toBe(true);
  await act(async () => {
    pending.resolve(response("010-0000-0031"));
  });
  expect(screen.queryByText(/010-0000-0031/)).not.toBeInTheDocument();
});
