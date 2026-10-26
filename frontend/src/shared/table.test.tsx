import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { DataTable, Pagination } from "./table";
import { usePreferences } from "./preferences";
import styles from "./ui.module.css";

const columns = [
  { key: "id", title: "코드", render: (row: { id: string }) => row.id },
];
beforeEach(() => {
  localStorage.clear();
  usePreferences.getState().update({ dense: false, pageSize: 50 });
});

describe("목록 상태와 도구줄", () => {
  it("첫 조회 중에는 스켈레톤만 보이고 빈 결과나 0건으로 오인하지 않는다", () => {
    render(
      <DataTable
        rows={[]}
        columns={columns}
        rowKey={(row) => row.id}
        total={0}
        loading
      />,
    );
    expect(
      screen.getByRole("status", { name: "목록 불러오는 중" }),
    ).toBeVisible();
    expect(
      screen.queryByText("조건에 맞는 결과가 없습니다"),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("총 0건")).not.toBeInTheDocument();
  });

  it("실패한 최초 조회는 빈 결과로 표시하지 않는다", () => {
    render(
      <DataTable
        rows={[]}
        columns={columns}
        rowKey={(row) => row.id}
        total={0}
        error
      />,
    );
    expect(
      screen.queryByText("조건에 맞는 결과가 없습니다"),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("성공한 0건 조회에만 지정 빈 결과를 표시한다", () => {
    render(
      <DataTable
        rows={[]}
        columns={columns}
        rowKey={(row) => row.id}
        total={0}
      />,
    );
    expect(screen.getByText("조건에 맞는 결과가 없습니다")).toBeVisible();
    expect(screen.getByText("총 0건")).toBeVisible();
  });

  it("재조회 중에는 기존 행을 유지하며 좁게 보기 설정을 현재 표에 반영한다", () => {
    render(
      <DataTable
        rows={[{ id: "BE" }]}
        columns={columns}
        rowKey={(row) => row.id}
        total={1}
        loading
      />,
    );
    expect(screen.getByRole("cell", { name: "BE" })).toBeVisible();
    fireEvent.click(screen.getByRole("checkbox", { name: /좁게 보기/ }));
    expect(screen.getByRole("table")).toHaveClass(styles.dense);
    expect(JSON.parse(localStorage.getItem("si.preferences")!)).toMatchObject({
      dense: true,
    });
  });
});

describe("서버 total 기반 페이지 이동", () => {
  it("조회 범위, 첫/끝 이동과 건수 변경을 연결한다", () => {
    const onPage = vi.fn();
    const onSize = vi.fn();
    render(
      <Pagination
        page={1}
        size={50}
        total={118}
        onPage={onPage}
        onSize={onSize}
      />,
    );
    expect(screen.getByText("51-100 / 118건")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "첫 페이지" }));
    expect(onPage).toHaveBeenLastCalledWith(0);
    fireEvent.click(screen.getByRole("button", { name: "마지막 페이지" }));
    expect(onPage).toHaveBeenLastCalledWith(2);
    expect(screen.getByRole("button", { name: "2" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    fireEvent.change(screen.getByRole("combobox", { name: "목록 건수" }), {
      target: { value: "20" },
    });
    expect(onSize).toHaveBeenCalledWith(20);
    expect(onPage).toHaveBeenLastCalledWith(0);
  });

  it("빈 결과에서는 모든 방향 버튼을 비활성화한다", () => {
    render(
      <Pagination
        page={0}
        size={50}
        total={0}
        onPage={vi.fn()}
        onSize={vi.fn()}
      />,
    );
    expect(screen.getByText("0-0 / 0건")).toBeVisible();
    for (const name of ["첫 페이지", "이전", "다음", "마지막 페이지"]) {
      expect(screen.getByRole("button", { name })).toBeDisabled();
    }
  });
});
