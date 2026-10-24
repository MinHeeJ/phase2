import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SalesPartStateDialog } from "./SalesPartStateDialog";
import type { SalesPartDetail } from "../salesparts/client";

const part: SalesPartDetail = {
  bsn_part_id: "2",
  bsn_part_nm: "영업 파트",
  hq_yn: "N",
  use_yn: "Y",
  sort_seq: 1,
  referenceMonth: "2026-10",
  currentStandard: null,
  scheduledChanges: [],
  peopleCount: 0,
  projectCount: 0,
  standards: [],
  lastClosedMonth: null,
  firstAllowedMonth: null,
};
const response = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
const conflict = (code: string, message: string) =>
  new Response(
    JSON.stringify({
      success: false,
      meta: {},
      code,
      message,
      field: null,
      requestId: "disable-request",
    }),
    { status: 409, headers: { "Content-Type": "application/json" } },
  );
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetcher = vi.fn(async () => response({ ...part, use_yn: "N" }));
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
function open(value = part) {
  const reload = vi.fn(async () => {}),
    onClose = vi.fn(),
    onSaved = vi.fn();
  render(
    <SalesPartStateDialog
      part={value}
      action="disable"
      reload={reload}
      onClose={onClose}
      onSaved={onSaved}
    />,
  );
  return { reload, onClose, onSaved };
}

describe("SCR-UI-15 part disable confirmation", () => {
  it("opens a danger confirmation without a mutation and cancel only closes", () => {
    const { onClose, reload, onSaved } = open();
    expect(
      screen.getByRole("dialog", { name: "영업 파트의 사용을 중지할까요?" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText("중지하면 새 인력·프로젝트에 고를 수 없습니다."),
    ).toBeInTheDocument();
    expect(screen.getByTestId("sales-part-state-confirm").className).toContain(
      "danger",
    );
    expect(fetcher).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "취소" }));
    expect(onClose).toHaveBeenCalledOnce();
    expect(reload).not.toHaveBeenCalled();
    expect(onSaved).not.toHaveBeenCalled();
    expect(fetcher).not.toHaveBeenCalled();
  });
  it.each([
    [3, 0],
    [0, 1],
    [3, 1],
  ])(
    "known people %s projects %s explain movement without requesting disable",
    (peopleCount, projectCount) => {
      const { onClose, onSaved } = open({ ...part, peopleCount, projectCount });
      expect(
        screen.getByRole("dialog", { name: "사용을 중지할 수 없습니다" }),
      ).toBeInTheDocument();
      expect(
        screen.getByText(
          `영업 파트에 소속 인력 ${peopleCount}명, 진행 프로젝트 ${projectCount}건이 있습니다. 먼저 다른 파트로 옮겨 주세요.`,
        ),
      ).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "사용 중지" })).toBeNull();
      fireEvent.click(screen.getByRole("button", { name: "확인" }));
      expect(onClose).toHaveBeenCalledOnce();
      expect(onSaved).not.toHaveBeenCalled();
      expect(fetcher).not.toHaveBeenCalled();
    },
  );
  it.each([
    [3, 0],
    [0, 1],
  ])(
    "server conflict shows authoritative counts %s/%s and never announces success",
    async (peopleCount, projectCount) => {
      fetcher.mockResolvedValueOnce(
        conflict(
          "PART_IN_USE",
          `영업 파트에 소속 인력 ${peopleCount}명, 진행 프로젝트 ${projectCount}건이 있습니다. 먼저 다른 파트로 옮겨 주세요.`,
        ),
      );
      const { reload, onSaved, onClose } = open();
      fireEvent.click(screen.getByRole("button", { name: "사용 중지" }));
      await screen.findByRole("dialog", { name: "사용을 중지할 수 없습니다" });
      expect(
        screen.getByText(
          new RegExp(
            `소속 인력 ${peopleCount}명, 진행 프로젝트 ${projectCount}건`,
          ),
        ),
      ).toBeInTheDocument();
      expect(screen.queryByTestId("sales-part-state-confirm")).toBeNull();
      expect(reload).not.toHaveBeenCalled();
      expect(onSaved).not.toHaveBeenCalled();
      fireEvent.click(screen.getByRole("button", { name: "확인" }));
      expect(onClose).toHaveBeenCalledOnce();
      expect(fetcher).toHaveBeenCalledOnce();
    },
  );
  it("serializes confirmation and waits for both refreshed views before success", async () => {
    let finish: (value: Response) => void = () => {};
    fetcher.mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    );
    const { reload, onSaved, onClose } = open();
    const confirm = screen.getByTestId("sales-part-state-confirm");
    fireEvent.click(confirm);
    fireEvent.click(confirm);
    expect(fetcher).toHaveBeenCalledOnce();
    expect(screen.getByTestId("sales-part-state-cancel")).toBeDisabled();
    expect(onClose).not.toHaveBeenCalled();
    expect(onSaved).not.toHaveBeenCalled();
    await act(async () => finish(response({ ...part, use_yn: "N" })));
    await waitFor(() =>
      expect(onSaved).toHaveBeenCalledWith("사용을 중지했습니다."),
    );
    expect(reload).toHaveBeenCalledOnce();
    expect(reload.mock.invocationCallOrder[0]).toBeLessThan(
      onSaved.mock.invocationCallOrder[0],
    );
    expect(fetcher).toHaveBeenCalledWith(
      "/api/sales-parts/2/disable",
      expect.objectContaining({ method: "POST" }),
    );
    expect(fetcher.mock.calls[0][1].body).toBeUndefined();
  });
  it("headquarters conflict remains an error instead of a success", async () => {
    fetcher.mockResolvedValueOnce(
      conflict("HQ_PART_LOCKED", "본사 파트는 변경할 수 없습니다."),
    );
    const { onSaved, reload } = open();
    fireEvent.click(screen.getByRole("button", { name: "사용 중지" }));
    expect(
      await screen.findByText("본사 파트는 변경할 수 없습니다."),
    ).toBeInTheDocument();
    expect(onSaved).not.toHaveBeenCalled();
    expect(reload).not.toHaveBeenCalled();
  });
  it("failed refresh after successful disable only retries reads", async () => {
    const { onSaved, reload } = open();
    reload.mockRejectedValueOnce(new Error("refresh failed"));
    fireEvent.click(screen.getByRole("button", { name: "사용 중지" }));
    await screen.findByText(
      "저장 후 조회에 실패했습니다. 현재 상태를 확인하세요.",
    );
    expect(onSaved).not.toHaveBeenCalled();
    expect(screen.getByTestId("sales-part-state-confirm")).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(2));
    expect(fetcher).toHaveBeenCalledOnce();
  });
});
