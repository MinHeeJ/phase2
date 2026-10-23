import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ActionBar,
  Badge,
  Button,
  ConfirmDialog,
  DataTable,
  EmptyState,
  LockedBanner,
  Modal,
  Pagination,
  SearchBar,
  Skeleton,
  SuccessToast,
  Tabs,
} from "../src/shared/Controls";
import { Can } from "../src/shared/Can";
import { currentScope, currentUser } from "../src/shared/types";

afterEach(() => vi.useRealTimers());
describe("shared controls", () => {
  it("permissions remain visible and scope is ALL", () => {
    render(
      <Can perm="UI-27.delete">
        <button>중지</button>
      </Can>,
    );
    expect(screen.getByText("중지")).toBeVisible();
    expect(currentScope).toBe("ALL");
    expect(currentUser).toEqual({
      userId: null,
      name: "기본 사용자",
      roleIds: [1],
    });
  });
  it("pending button shows spinner and cannot submit twice", () => {
    const click = vi.fn();
    render(
      <Button pending onClick={click}>
        저장
      </Button>,
    );
    fireEvent.click(screen.getByText("저장"));
    expect(click).not.toHaveBeenCalled();
    expect(screen.getByLabelText("처리 중")).toBeInTheDocument();
    expect(screen.getByRole("button")).toBeDisabled();
  });
  it("places one primary action at the end", () => {
    const { container } = render(
      <ActionBar primary={<Button variant="primary">저장</Button>}>
        <Button>취소</Button>
      </ActionBar>,
    );
    expect(container.querySelectorAll("button")).toHaveLength(2);
    expect(container.firstElementChild?.firstElementChild).toHaveTextContent(
      "취소",
    );
    expect(screen.getByText("저장").parentElement).toBe(
      container.firstElementChild?.lastElementChild,
    );
  });
  it("sensitive values are not masked and all alignments are assigned", () => {
    render(
      <DataTable
        total={1}
        rows={[{ id: "one" }]}
        rowKey={(row) => row.id}
        columns={["number", "text", "code", "status", "date"].map((kind) => ({
          key: kind,
          label: kind,
          kind: kind as "number",
          sensitive: true,
          render: () => "200000",
        }))}
      />,
    );
    expect(screen.getAllByText("200000")).toHaveLength(5);
    for (const kind of ["number", "text", "code", "status", "date"])
      expect(
        screen.getByRole("columnheader", { name: kind }).className,
      ).toContain(kind);
  });
  it("toggles dense table", () => {
    const dense = vi.fn();
    render(
      <DataTable
        total={0}
        rows={[]}
        rowKey={() => ""}
        columns={[]}
        dense
        onDenseChange={dense}
      />,
    );
    fireEvent.click(screen.getByLabelText("좁게 보기"));
    expect(dense).toHaveBeenCalledWith(false);
    expect(screen.getByRole("table").className).toContain("dense");
    expect(screen.getByText("총 0건")).toBeVisible();
  });
  it("pager has at most ten numbers and 20/50/100", () => {
    render(
      <Pagination
        page={1}
        size={20}
        total={500}
        onPage={() => {}}
        onSize={() => {}}
      />,
    );
    expect(screen.getAllByRole("button")).toHaveLength(14);
    expect(screen.getByLabelText("첫 페이지")).toBeDisabled();
    expect(screen.getAllByRole("option")).toHaveLength(3);
    expect(screen.getByRole("button", { name: /^1$/ })).toHaveAttribute(
      "aria-current",
      "page",
    );
  });
  it("pager forwards server totals, next page and size", () => {
    const page = vi.fn(),
      size = vi.fn();
    render(
      <Pagination page={2} size={50} total={103} onPage={page} onSize={size} />,
    );
    expect(screen.getByText("51~100 / 전체 103건")).toBeVisible();
    fireEvent.click(screen.getByLabelText("다음 페이지"));
    expect(page).toHaveBeenCalledWith(3);
    fireEvent.change(screen.getByLabelText("목록 건수"), {
      target: { value: "100" },
    });
    expect(size).toHaveBeenCalledWith(100);
  });
  it("search form submits on Enter via form and reset is independent", () => {
    const search = vi.fn(),
      reset = vi.fn();
    render(
      <SearchBar onSearch={search} onReset={reset}>
        <input aria-label="검색" />
      </SearchBar>,
    );
    fireEvent.submit(screen.getByLabelText("검색").closest("form")!);
    expect(search).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByText("초기화"));
    expect(reset).toHaveBeenCalledOnce();
  });
  it("tabs have max five and emit selection", () => {
    const change = vi.fn();
    render(
      <Tabs
        items={[
          { key: "codes", label: "코드" },
          { key: "history", label: "이력" },
        ]}
        selected="codes"
        onChange={change}
      />,
    );
    expect(screen.getByText("코드")).toHaveAttribute("aria-selected", "true");
    fireEvent.click(screen.getByText("이력"));
    expect(change).toHaveBeenCalledWith("history");
  });
  it("work modal is retained on backdrop click", () => {
    const cancel = vi.fn();
    const { container } = render(
      <Modal title="값 변경" onCancel={cancel}>
        <input aria-label="새 값" />
      </Modal>,
    );
    fireEvent.click(container.firstElementChild!);
    expect(cancel).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog")).toBeVisible();
  });
  it("confirmation contains the destructive action", () => {
    const confirm = vi.fn();
    render(
      <ConfirmDialog
        title="확인"
        onConfirm={confirm}
        onCancel={() => {}}
        destructive
      >
        중지하시겠습니까?
      </ConfirmDialog>,
    );
    fireEvent.click(screen.getByRole("button", { name: "확인" }));
    expect(confirm).toHaveBeenCalledOnce();
    expect(screen.getByRole("dialog").className).toContain("confirmation");
  });
  it("success disappears after three seconds", () => {
    vi.useFakeTimers();
    const dismiss = vi.fn();
    render(<SuccessToast message="저장했습니다." onDismiss={dismiss} />);
    expect(screen.getByRole("status")).toHaveTextContent("저장했습니다.");
    vi.advanceTimersByTime(3000);
    expect(dismiss).toHaveBeenCalledOnce();
  });
  it("provides loading, empty action, locked banner and all status tones", () => {
    const action = vi.fn();
    render(
      <>
        <Skeleton />
        <EmptyState
          reason="등록한 항목이 없습니다."
          actionLabel="추가"
          onAction={action}
        />
        <LockedBanner>마감되었습니다.</LockedBanner>
        {(
          ["success", "warning", "danger", "info", "locked", "neutral"] as const
        ).map((tone) => (
          <Badge key={tone} tone={tone}>
            {tone}
          </Badge>
        ))}
      </>,
    );
    expect(screen.getByLabelText("불러오는 중")).toBeVisible();
    fireEvent.click(screen.getByText("추가"));
    expect(action).toHaveBeenCalledOnce();
    expect(screen.getByText("마감되었습니다.")).toBeVisible();
    for (const tone of [
      "success",
      "warning",
      "danger",
      "info",
      "locked",
      "neutral",
    ])
      expect(screen.getByText(tone).className).toContain(tone);
  });
});
