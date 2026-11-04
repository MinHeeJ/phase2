import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, useNavigate } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import UI08Screen from "../../src/pages/profit/UI08Screen";
import UI18Screen from "../../src/pages/businessplan/UI18Screen";
import UI28Screen from "../../src/pages/board/UI28Screen";
import UI21Screen from "../../src/pages/cashflow/UI21Screen";
import UI04Screen from "../../src/pages/projects/UI04Screen";
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  sessionStorage.clear();
  fetcher = vi.fn(() => new Promise<Response>(() => {}));
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => {
  vi.unstubAllGlobals();
});
function mount(
  element: React.ReactElement,
  path: string,
  state = { closingMonth: "2026-03" } as Record<string, unknown>,
) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: path, state }]}>
      {element}
    </MemoryRouter>,
  );
}
it("UI-08 consumes the selected year/month in its original period controls", async () => {
  sessionStorage.setItem("profit-ui-08-period", "2026-10");
  mount(<UI08Screen />, "/UI-08");
  expect(screen.getByLabelText("누계 기준월")).toHaveValue("3");
  await waitFor(() =>
    expect(
      fetcher.mock.calls.some(([path]) =>
        String(path).includes("/profit/monthly?year=2026"),
      ),
    ).toBe(true),
  );
});
it("UI-18 consumes the selected cumulative month instead of the remembered period", () => {
  sessionStorage.setItem("business-plan-period", "2026-10");
  mount(<UI18Screen />, "/UI-18");
  expect(screen.getByTestId("business-plan-month")).toHaveValue("3");
});
it("UI-28 retains annual scope and names the relevant handoff month", async () => {
  mount(<UI28Screen />, "/UI-28");
  expect(screen.getByRole("status", { name: "" })).toHaveTextContent(
    "대시보드 기준월 2026-03",
  );
  await waitFor(() =>
    expect(
      fetcher.mock.calls.some(([path]) => String(path).includes("year=2026")),
    ).toBe(true),
  );
});
it("UI-21 explicitly retains annual scope for the selected forecast month", async () => {
  mount(<UI21Screen />, "/UI-21", { closingMonth: "2027-01" });
  expect(screen.getByText(/대시보드 기준월 2027-01/)).toBeInTheDocument();
  expect(screen.getByText("2027년")).toBeInTheDocument();
  await waitFor(() =>
    expect(
      fetcher.mock.calls.some(([path]) => String(path).includes("year=2027")),
    ).toBe(true),
  );
});
it("UI-04 opens the real selected project causes endpoint", async () => {
  mount(<UI04Screen />, "/UI-04", { closingMonth: "2026-03", projectId: 47 });
  await waitFor(() =>
    expect(
      fetcher.mock.calls.some(
        ([path]) => String(path) === "/api/costs/projects/47/causes?year=2026",
      ),
    ).toBe(true),
  );
  expect(screen.getByRole("tab", { name: "원가 초과 원인" })).toHaveAttribute(
    "aria-selected",
    "true",
  );
});

function HandoffNavigation({ target }: { target: string }) {
  const navigate = useNavigate();
  return (
    <>
      <button
        data-testid="handoff-other-screen"
        onClick={() =>
          navigate("/UI-16", {
            state: { closingMonth: "2026-03", projectId: 47 },
          })
        }
      >
        다른 화면 이동
      </button>
      <button
        data-testid="handoff-target-screen"
        onClick={() => navigate(target, { state: { closingMonth: "2026-03" } })}
      >
        대상 화면 이동
      </button>
    </>
  );
}

it.each([
  { path: "/UI-08", element: <UI08Screen /> },
  { path: "/UI-18", element: <UI18Screen /> },
  { path: "/UI-28", element: <UI28Screen /> },
  { path: "/UI-21", element: <UI21Screen /> },
  { path: "/UI-04", element: <UI04Screen /> },
])(
  "$path ignores another tab's handoff while staying mounted",
  async ({ path, element }) => {
    mount(
      <>
        {element}
        <HandoffNavigation target={path} />
      </>,
      path,
      { closingMonth: "2024-03" },
    );
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) => String(url).includes("year=2024")),
      ).toBe(true),
    );
    const count = fetcher.mock.calls.length;
    fireEvent.click(screen.getByTestId("handoff-other-screen"));
    expect(fetcher).toHaveBeenCalledTimes(count);
    expect(
      screen.queryByRole("tab", { name: "원가 초과 원인" }),
    ).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId("handoff-target-screen"));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([url]) => String(url).includes("year=2026")),
      ).toBe(true),
    );
  },
);
