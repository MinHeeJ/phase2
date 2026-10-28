import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import UI11Screen from "./UI11Screen";

vi.mock("../admin/useAdminResource", () => ({
  useAdminRead: () => ({
    data: null,
    loading: false,
    error: null,
    reload: vi.fn(),
  }),
}));
vi.mock("../shared/useCodes", () => ({
  useCodes: () => ({ codes: [], error: null, retry: vi.fn() }),
}));
vi.mock("../us5/ShortagesPanel", () => ({
  ShortagesPanel: ({
    year,
    onOpenProject,
  }: {
    year: number;
    onOpenProject: (id: number) => void;
  }) => (
    <div>
      <span>미충원 {year}</span>
      <button onClick={() => onOpenProject(42)}>프로젝트 열기</button>
    </div>
  ),
}));

it("switches to shortages and opens the selected project", () => {
  const openProject = vi.fn();
  render(<UI11Screen openProject={openProject} />);
  expect(screen.getByTestId("comparison-gap")).toBeTruthy();
  fireEvent.click(screen.getByRole("tab", { name: "미충원 직무" }));
  expect(screen.queryByTestId("comparison-gap")).toBeNull();
  expect(screen.queryByTestId("comparison-export")).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "프로젝트 열기" }));
  expect(openProject).toHaveBeenCalledWith(42);
});
