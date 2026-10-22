import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ResponsiveLayout } from "../src/app/ResponsiveLayout";

describe("responsive regions", () => {
  it("keeps header/menu/content/tabs as separate regions", () => {
    const { container } = render(
      <ResponsiveLayout
        header="프로젝트·원가 관리"
        menu="메뉴"
        tabs="열린 화면"
        collapsed
      >
        <input aria-label="업무 입력" />
      </ResponsiveLayout>,
    );
    expect(screen.getByRole("banner")).toHaveTextContent("프로젝트·원가 관리");
    expect(screen.getByLabelText("업무 메뉴")).toHaveTextContent("메뉴");
    expect(container.firstElementChild?.className).toContain("collapsed");
    expect(screen.getByText("열린 화면").className).toContain("screenTabs");
  });
  it("click expands and closes menu without losing content", () => {
    const { container } = render(
      <ResponsiveLayout header="제목" menu="메뉴">
        <input aria-label="입력" defaultValue="보존" />
      </ResponsiveLayout>,
    );
    fireEvent.click(screen.getByLabelText("메뉴 열기"));
    expect(container.firstElementChild?.className).toContain("expanded");
    expect(screen.getByLabelText("메뉴 닫기")).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    fireEvent.click(screen.getByLabelText("메뉴 닫기"));
    expect(screen.getByLabelText("입력")).toHaveValue("보존");
  });
});
