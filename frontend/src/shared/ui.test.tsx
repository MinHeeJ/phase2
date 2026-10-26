import { render, screen, fireEvent, act } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import {
  Button,
  Dialog,
  ConfirmDialog,
  EmptyState,
  Notice,
  Skeleton,
  SuccessToast,
  Tabs,
  Badge,
  SearchForm,
} from "./ui";
import { Can } from "./permissions";
import { ErrorBanner } from "./ErrorBanner";
import { ApiFailure, NetworkFailure } from "./http";
import { DataTable, Pagination } from "./table";
import styles from "./ui.module.css";

describe("공통 부품", () => {
  it("현재 모든 권한 키는 버튼을 표시한다", () => {
    render(
      <Can perm="UI-27.delete">
        <Button>사용 중지</Button>
      </Can>,
    );
    expect(screen.getByRole("button", { name: "사용 중지" })).toBeVisible();
  });
  it("처리 중 버튼은 상태와 스피너를 표시하고 중복 클릭을 막는다", () => {
    const click = vi.fn();
    render(
      <Button pending onClick={click}>
        저장
      </Button>,
    );
    const button = screen.getByRole("button", { name: /저장/ });
    fireEvent.click(button);
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("aria-busy", "true");
    expect(click).not.toHaveBeenCalled();
  });
  it("확인창 안에서만 위험 동작을 확인한다", () => {
    const confirm = vi.fn();
    render(
      <ConfirmDialog
        title="사용 중지"
        destructive
        onConfirm={confirm}
        onClose={() => undefined}
      >
        계속하시겠습니까?
      </ConfirmDialog>,
    );
    expect(screen.getByRole("button", { name: "확인" })).toHaveClass(
      styles.dangerButton,
    );
    fireEvent.click(screen.getByRole("button", { name: "확인" }));
    expect(confirm).toHaveBeenCalledTimes(1);
  });
  it("작업창 바깥 클릭은 닫지 않는다", () => {
    const close = vi.fn();
    render(
      <Dialog title="그룹 추가" onClose={close}>
        <input aria-label="그룹명" />
      </Dialog>,
    );
    fireEvent.click(screen.getByRole("dialog").parentElement!);
    expect(close).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "닫기" }));
    expect(close).toHaveBeenCalledTimes(1);
  });
  it("오류 띠는 문의 번호와 재시도를 제공하고 편집 입력을 유지한다", () => {
    const retry = vi.fn();
    render(
      <>
        <input aria-label="편집" defaultValue="작성 중" />
        <ErrorBanner
          error={
            new ApiFailure(
              {
                success: false,
                meta: {},
                code: "INTERNAL_ERROR",
                message: "오류",
                requestId: "trace-1",
              },
              500,
            )
          }
          onRetry={retry}
        />
      </>,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("문의 번호: trace-1");
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    expect(retry).toHaveBeenCalledTimes(1);
    expect(screen.getByLabelText("편집")).toHaveValue("작성 중");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });
  it("미도달 오류에는 문의 번호를 발명하지 않는다", () => {
    render(
      <ErrorBanner error={new NetworkFailure()} onRetry={() => undefined} />,
    );
    expect(screen.getByRole("alert")).not.toHaveTextContent("문의 번호");
  });
  it("숫자 정렬과 현재 미마스킹을 보존한다", () => {
    render(
      <DataTable
        rows={[{ id: "row", amount: 200000 }]}
        total={1}
        rowKey={(row) => row.id}
        dense
        columns={[
          {
            key: "amount",
            title: "급여",
            kind: "number",
            sensitive: true,
            render: (row) => row.amount,
          },
          { key: "id", title: "코드", kind: "code", render: (row) => row.id },
        ]}
      />,
    );
    expect(screen.getByRole("cell", { name: "200000" })).toHaveClass(
      styles.number,
    );
    expect(screen.getByRole("cell", { name: "row" })).toHaveClass(styles.code);
    expect(screen.getByRole("table")).toHaveClass(styles.dense);
    expect(screen.getByText("총 1건")).toBeVisible();
  });
  it("페이지 번호는 API 인덱스보다 하나 크고 최대 열 개다", () => {
    const page = vi.fn();
    const size = vi.fn();
    render(
      <Pagination
        page={0}
        size={50}
        total={1000}
        onPage={page}
        onSize={size}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /^2$/ }));
    expect(page).toHaveBeenLastCalledWith(1);
    expect(
      screen.queryByRole("button", { name: /^11$/ }),
    ).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole("combobox"), {
      target: { value: "100" },
    });
    expect(size).toHaveBeenCalledWith(100);
  });
  it("성공 토스트는 3초 후 사라진다", () => {
    vi.useFakeTimers();
    const close = vi.fn();
    render(<SuccessToast message="저장했습니다." onClose={close} />);
    act(() => vi.advanceTimersByTime(2999));
    expect(close).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(1));
    expect(close).toHaveBeenCalledTimes(1);
    vi.useRealTimers();
  });
  it("빈 상태는 이유와 할 일을 안내하고 로딩과 안내 상태를 구분한다", () => {
    render(
      <>
        <EmptyState
          reason="등록된 대응이 없습니다."
          nextAction={<Button>대응 추가</Button>}
        />
        <Notice>조회 전용입니다.</Notice>
        <Skeleton />
      </>,
    );
    expect(screen.getByText("등록된 대응이 없습니다.")).toBeVisible();
    expect(screen.getByRole("button", { name: "대응 추가" })).toBeVisible();
    expect(screen.getByRole("status", { name: "불러오는 중" })).toBeVisible();
    expect(screen.getByText("조회 전용입니다.")).toHaveClass(styles.notice);
  });
  it("탭과 다섯 가지 상태 배지를 표시한다", () => {
    const change = vi.fn();
    render(
      <>
        <Tabs
          items={[
            { key: "codes", label: "코드" },
            { key: "history", label: "변경 이력" },
          ]}
          value="codes"
          onChange={change}
        />
        {(["success", "warning", "danger", "info", "locked"] as const).map(
          (tone) => (
            <Badge key={tone} tone={tone}>
              {tone}
            </Badge>
          ),
        )}
      </>,
    );
    expect(screen.getByRole("tab", { name: "코드" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    fireEvent.click(screen.getByRole("tab", { name: "변경 이력" }));
    expect(change).toHaveBeenCalledWith("history");
    expect(screen.getByText("locked")).toHaveClass(styles.locked);
  });
  it("Enter 조회와 초기화를 연결한다", () => {
    const search = vi.fn();
    const reset = vi.fn();
    render(
      <SearchForm onSearch={search} onReset={reset}>
        <input aria-label="검색" />
      </SearchForm>,
    );
    fireEvent.submit(
      screen.getByRole("button", { name: "조회" }).closest("form")!,
    );
    expect(search).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "초기화" }));
    expect(reset).toHaveBeenCalledTimes(1);
  });
});
