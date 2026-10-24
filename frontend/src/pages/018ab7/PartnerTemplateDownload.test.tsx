import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UI15Screen from "../partners/UI15Screen";
import { UploadDialogs } from "../uploadsops/UploadDialogs";
import { PartnerTemplateDownloadButton } from "../uploadsops/PartnerTemplateDownloadButton";

const ok = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const templatePath = "/api/uploads/templates/B.7";
let fetcher: ReturnType<typeof vi.fn>;
let click: ReturnType<typeof vi.spyOn>;
let savedNames: string[];
beforeEach(() => {
  savedNames = [];
  fetcher = vi.fn(async (url: string) => {
    if (url === templatePath)
      return new Response(new Uint8Array([0x50, 0x4b, 3, 4]), {
        status: 200,
        headers: {
          "Content-Type":
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        },
      });
    if (url.startsWith("/api/codes/")) return ok([]);
    if (url.startsWith("/api/partners?"))
      return ok({ items: [], total: 0, page: 1, size: 50 });
    throw new Error(`Unexpected test request ${url}`);
  });
  vi.stubGlobal("fetch", fetcher);
  Object.defineProperty(URL, "createObjectURL", {
    configurable: true,
    value: vi.fn(() => "blob:partner-template"),
  });
  Object.defineProperty(URL, "revokeObjectURL", {
    configurable: true,
    value: vi.fn(),
  });
  click = vi
    .spyOn(HTMLAnchorElement.prototype, "click")
    .mockImplementation(function (this: HTMLAnchorElement) {
      savedNames.push(this.download);
      expect(this.href).toBe("blob:partner-template");
    });
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function expectDownload() {
  await waitFor(() => expect(click).toHaveBeenCalledOnce());
  expect(savedNames).toEqual(["거래처_업로드양식.xlsx"]);
  expect(URL.createObjectURL).toHaveBeenCalledWith(
    expect.objectContaining({ size: expect.any(Number) }),
  );
  expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:partner-template");
  expect(document.querySelector("a[download]")).toBeNull();
}

describe("B.7 template download", () => {
  it("downloads from the existing UI-15 heading without leaving the screen or refreshing data", async () => {
    render(<UI15Screen />);
    await screen.findByText("조건에 맞는 결과가 없습니다.");
    const reads = fetcher.mock.calls.filter(([url]) =>
      url.startsWith("/api/partners?"),
    ).length;
    fireEvent.click(screen.getByTestId("partner-template-download"));
    await expectDownload();
    expect(
      fetcher.mock.calls.filter(([url]) => url === templatePath),
    ).toHaveLength(1);
    expect(
      fetcher.mock.calls.filter(([url]) => url.startsWith("/api/partners?")),
    ).toHaveLength(reads);
    expect(screen.getByTestId("partners-screen")).toBeVisible();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
  it("the upload selection uses the same relative API and real filename without validating a file", async () => {
    render(<UploadDialogs onClose={vi.fn()} onApplied={vi.fn()} />);
    fireEvent.click(screen.getByTestId("upload-template"));
    await expectDownload();
    expect(fetcher).toHaveBeenCalledWith(templatePath);
    expect(screen.getByTestId("upload-submit")).toBeDisabled();
    expect(screen.getByTestId("upload-selection")).toBeVisible();
  });
  it("blocks duplicate heading downloads while the response is pending", async () => {
    let finish!: (value: Response) => void;
    fetcher.mockImplementation(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    );
    render(<PartnerTemplateDownloadButton />);
    const button = screen.getByRole("link", { name: "양식 내려받기" });
    fireEvent.click(button);
    expect(button).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(button);
    expect(fetcher).toHaveBeenCalledOnce();
    finish(new Response("xlsx", { status: 200 }));
    await expectDownload();
    expect(button).toHaveAttribute("aria-disabled", "false");
  });
  it("a later deliberate request downloads again instead of caching away the access record", async () => {
    render(<PartnerTemplateDownloadButton />);
    const button = screen.getByTestId("partner-template-download");
    fireEvent.click(button);
    await expectDownload();
    await waitFor(() =>
      expect(button).toHaveAttribute("aria-disabled", "false"),
    );
    fireEvent.click(button);
    await waitFor(() => expect(click).toHaveBeenCalledTimes(2));
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it("a missing template shows the server error without saving an error JSON as xlsx", async () => {
    fetcher.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          success: false,
          message: "업로드 양식이 없습니다.",
          requestId: "template-request",
        }),
        { status: 404 },
      ),
    );
    render(<PartnerTemplateDownloadButton />);
    fireEvent.click(screen.getByTestId("partner-template-download"));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "업로드 양식이 없습니다.",
    );
    expect(screen.getByRole("alert")).toHaveTextContent(
      "문의 번호 template-request",
    );
    expect(click).not.toHaveBeenCalled();
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });
  it("a network failure is recoverable through the visible retry action", async () => {
    fetcher.mockRejectedValueOnce(new Error("offline"));
    render(<PartnerTemplateDownloadButton />);
    fireEvent.click(screen.getByTestId("partner-template-download"));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "파일을 내려받을 수 없습니다.",
    );
    expect(click).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await expectDownload();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
  it("a non-JSON server failure stays in the current screen with no partial download", async () => {
    fetcher.mockResolvedValueOnce(new Response("unavailable", { status: 500 }));
    render(<PartnerTemplateDownloadButton />);
    fireEvent.click(screen.getByTestId("partner-template-download"));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "파일을 내려받을 수 없습니다.",
    );
    expect(screen.getByTestId("partner-template-download")).toHaveAttribute(
      "aria-disabled",
      "false",
    );
    expect(click).not.toHaveBeenCalled();
  });
  it("selection download failure preserves the chosen upload file", async () => {
    render(<UploadDialogs onClose={vi.fn()} onApplied={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("파일 선택"), {
      target: { files: [new File(["xlsx"], "거래처.xlsx")] },
    });
    fetcher.mockResolvedValueOnce(
      new Response(JSON.stringify({ message: "양식 생성에 실패했습니다." }), {
        status: 500,
      }),
    );
    fireEvent.click(screen.getByTestId("upload-template"));
    expect(await screen.findByText("양식 생성에 실패했습니다.")).toBeVisible();
    expect(screen.getByText("거래처.xlsx")).toBeVisible();
    expect(screen.getByTestId("upload-submit")).toBeEnabled();
    expect(click).not.toHaveBeenCalled();
  });
});
