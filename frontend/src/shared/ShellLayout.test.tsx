import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ShellLayout } from "./ShellLayout";
import styles from "./layout.module.css";

describe("반응형 shell 상태", () => {
  it("헤더 메뉴 본문 탭 영역을 분리한다", () => {
    render(
      <ShellLayout
        header="프로젝트·원가 관리"
        navigation={<nav>업무 메뉴</nav>}
        tabs="열린 화면"
        collapsed={false}
        onToggle={() => undefined}
      >
        화면 내용
      </ShellLayout>,
    );
    expect(screen.getByRole("banner")).toHaveTextContent("프로젝트·원가 관리");
    expect(screen.getByRole("complementary")).toHaveTextContent("업무 메뉴");
    expect(screen.getByRole("main")).toHaveTextContent("화면 내용");
    expect(screen.getByText("열린 화면")).toHaveClass(styles.screenTabs);
  });
  it("접힘 상태와 메뉴 확장 동작을 연결한다", () => {
    const toggle = vi.fn();
    const { container } = render(
      <ShellLayout
        header="제목"
        navigation="메뉴"
        tabs={null}
        collapsed
        onToggle={toggle}
      >
        내용
      </ShellLayout>,
    );
    expect(container.firstElementChild).toHaveClass(styles.collapsed);
    fireEvent.click(screen.getByRole("button", { name: "메뉴 접기/펼치기" }));
    expect(toggle).toHaveBeenCalledTimes(1);
  });
  it("모바일 전체 메뉴를 열고 화면 선택 후 닫는다", () => {
    const { container } = render(
      <ShellLayout
        header="제목"
        navigation={
          <a href="/UI-16" onClick={(event) => event.preventDefault()}>
            대시보드
          </a>
        }
        tabs={null}
        collapsed
        onToggle={() => undefined}
      >
        내용
      </ShellLayout>,
    );
    fireEvent.click(screen.getByRole("button", { name: "메뉴 열기" }));
    expect(container.firstElementChild).toHaveClass(styles.mobileOpen);
    fireEvent.click(screen.getByRole("link", { name: "대시보드" }));
    expect(container.firstElementChild).not.toHaveClass(styles.mobileOpen);
  });
});
