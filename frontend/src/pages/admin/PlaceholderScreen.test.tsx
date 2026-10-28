import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, afterEach, expect, it, vi } from "vitest";
import PlaceholderScreen from "./PlaceholderScreen";

const ids = [
  "UI-01",
  "UI-02",
  "UI-03",
  "UI-04",
  "UI-05",
  "UI-07",
  "UI-08",
  "UI-09",
  "UI-10",
  "UI-11",
  "UI-12",
  "UI-14",
  "UI-15",
  "UI-16",
  "UI-17",
  "UI-18",
  "UI-19",
  "UI-20",
  "UI-21",
  "UI-23",
  "UI-24",
  "UI-25",
  "UI-26",
  "UI-28",
  "UI-29",
  "UI-30",
  "UI-31",
  "UI-32",
  "UI-33",
];
const result = (data: unknown) =>
  new Response(JSON.stringify({ success: true, meta: {}, data }));
beforeEach(() => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) =>
      url.startsWith("/api/menus")
        ? result([
            {
              menuId: 1,
              name: "메뉴 묶음",
              screenId: null,
              mobile: true,
              children: ids.map((id, i) => ({
                menuId: i + 2,
                name: `시험 화면 ${id}`,
                screenId: id,
                mobile: ![
                  "UI-10",
                  "UI-14",
                  "UI-20",
                  "UI-23",
                  "UI-24",
                  "UI-25",
                ].includes(id),
                children: [],
              })),
            },
          ])
        : result([{ month: "2026-09", status: "CLOSED" }]),
    ),
  );
});
afterEach(() => vi.unstubAllGlobals());
it.each(ids)(
  "seed-backed %s renders menu name and next-phase content without business controls",
  async (id) => {
    render(<PlaceholderScreen screenId={id} />);
    expect(
      await screen.findByText("이 화면은 다음 단계에서 만듭니다."),
    ).toBeInTheDocument();
    expect(screen.getByRole("heading")).toHaveTextContent(`시험 화면 ${id}`);
    expect(
      screen.queryByRole("button", { name: "저장" }),
    ).not.toBeInTheDocument();
  },
);
it("UI-22 is unlisted compatibility and not login", async () => {
  render(<PlaceholderScreen screenId="UI-22" />);
  expect(
    await screen.findByText("이 화면은 다음 단계에서 만듭니다."),
  ).toBeInTheDocument();
  expect(screen.queryByLabelText("비밀번호")).not.toBeInTheDocument();
});
it("unknown route is not fabricated", async () => {
  render(<PlaceholderScreen screenId="UI-06" />);
  expect(
    await screen.findByText("화면을 찾을 수 없습니다."),
  ).toBeInTheDocument();
});
it("mobile=false assigns guard classes and dashboard navigation", async () => {
  const { container } = render(<PlaceholderScreen screenId="UI-14" />);
  expect(await screen.findByText("PC에서 이용해 주세요")).toBeInTheDocument();
  expect(screen.getByText("대시보드로")).toHaveAttribute("href", "/UI-16");
  expect(container.querySelector('[class*="desktopOnly"]')).not.toBeNull();
  expect(container.querySelector('[class*="mobileOnly"]')).not.toBeNull();
});
it("MONTH selection reads actual close rows and retains chosen month", async () => {
  render(<PlaceholderScreen screenId="UI-19" periodType="MONTH" />);
  await screen.findByLabelText("선택 기간");
  fireEvent.change(screen.getByLabelText("선택 기간"), {
    target: { value: "2026-09" },
  });
  expect(
    await screen.findByText(
      "2026-09는 마감되었습니다. 고칠 내용은 다음 달 전월 조정으로 입력하세요.",
    ),
  ).toBeVisible();
  expect(screen.getByLabelText("선택 기간")).toHaveValue("2026-09");
});
it("YEAR_CUM period displays cumulative selection", async () => {
  render(<PlaceholderScreen screenId="UI-07" periodType="YEAR_CUM" />);
  await screen.findByLabelText("선택 기간");
  fireEvent.change(screen.getByLabelText("선택 기간"), {
    target: { value: "2026-10" },
  });
  expect(screen.getByText("1~10월 누계")).toBeVisible();
});
it("failed menu read retries instead of inventing a title", async () => {
  const fetcher = vi.mocked(fetch);
  fetcher.mockRejectedValueOnce(new Error("offline"));
  render(<PlaceholderScreen screenId="UI-01" />);
  await screen.findByText("문의 번호 없음 (서버 응답 없음)");
  expect(screen.queryByRole("heading")).not.toBeInTheDocument();
  fireEvent.click(screen.getByText("다시 시도"));
  expect(await screen.findByRole("heading")).toHaveTextContent(
    "시험 화면 UI-01",
  );
});
it("favorite action uses selected real menu id", async () => {
  const star = vi.fn();
  render(<PlaceholderScreen screenId="UI-03" onFavorite={star} />);
  fireEvent.click(await screen.findByLabelText("즐겨찾기"));
  expect(star).toHaveBeenCalledWith("UI-03");
});
