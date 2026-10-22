import { render, screen, fireEvent, act } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import {
  Button,
  Badge,
  ConfirmDialog,
  DataTable,
  EmptyState,
  LockedBanner,
  Modal,
  Pagination,
  QueryBar,
  Skeleton,
  SuccessToast,
  Tabs,
} from "../src/components/UiKit";
import { Can } from "../src/components/Can";
import { AppFrame } from "../src/app/AppFrame";
import { useAccess } from "../src/stores/access";

describe("공통 UI 계약", () => {
  it("처리 중 버튼 스피너와 클릭 차단", () => {
    const click = vi.fn();
    render(
      <Button pending onClick={click}>
        저장
      </Button>,
    );
    fireEvent.click(screen.getByRole("button"));
    expect(click).not.toHaveBeenCalled();
    expect(screen.getByLabelText("처리 중")).toBeInTheDocument();
    expect(screen.getByRole("button")).toBeDisabled();
  });
  it("민감 열은 현재 무마스킹, 열 종류별 정렬 클래스", () => {
    render(
      <DataTable
        columns={[
          {
            key: "amount",
            title: "금액",
            kind: "number",
            sensitive: true,
            render: (row) => row.amount,
          },
          {
            key: "code",
            title: "코드",
            kind: "code",
            render: (row) => row.code,
          },
        ]}
        rows={[{ code: "BE", amount: 1500000 }]}
        rowKey={(row) => row.code}
        total={1}
      />,
    );
    expect(screen.getByRole("cell", { name: "1500000" })).toHaveAttribute(
      "data-sensitive",
      "true",
    );
    expect(screen.getByRole("cell", { name: "1500000" }).className).toContain(
      "number",
    );
    expect(screen.getByRole("cell", { name: "BE" }).className).toContain(
      "code",
    );
    expect(screen.getByText("총 1건")).toBeInTheDocument();
  });
  it("좁게 보기 제어", () => {
    const change = vi.fn();
    render(
      <DataTable
        columns={[{ key: "name", title: "이름", render: (row: string) => row }]}
        rows={[]}
        rowKey={(row) => row}
        total={0}
        onDenseChange={change}
      />,
    );
    fireEvent.click(screen.getByLabelText("좁게 보기"));
    expect(change).toHaveBeenCalledWith(true);
    expect(
      screen.getByText("조회 결과가 없습니다. 조건을 바꿔 다시 조회하세요."),
    ).toBeInTheDocument();
  });
  it("페이지 최대 10번호, 끝 비활성, 목록 건수", () => {
    const onPage = vi.fn(),
      onSize = vi.fn();
    render(
      <Pagination
        page={1}
        size={50}
        total={1000}
        onPage={onPage}
        onSize={onSize}
      />,
    );
    expect(screen.getByLabelText("이전 페이지")).toBeDisabled();
    expect(
      screen
        .getAllByRole("button")
        .filter((button) => /^\d+$/.test(button.textContent ?? "")),
    ).toHaveLength(10);
    expect(screen.getByRole("button", { name: "1" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    fireEvent.click(screen.getByLabelText("다음 페이지"));
    expect(onPage).toHaveBeenCalledWith(2);
    fireEvent.change(screen.getByLabelText("목록 건수"), {
      target: { value: "20" },
    });
    expect(onSize).toHaveBeenCalledWith(20);
  });
  it("검색/초기화/Enter 제출", () => {
    const search = vi.fn(),
      reset = vi.fn();
    render(
      <QueryBar onSearch={search} onReset={reset}>
        <input aria-label="검색" />
      </QueryBar>,
    );
    fireEvent.submit(screen.getByLabelText("검색").closest("form")!);
    expect(search).toHaveBeenCalledOnce();
    fireEvent.click(screen.getByText("초기화"));
    expect(reset).toHaveBeenCalledOnce();
  });
  it("선택 하위 탭과 변경 핸들러", () => {
    const change = vi.fn();
    render(
      <Tabs
        value="codes"
        onChange={change}
        items={[
          { key: "codes", label: "코드" },
          { key: "history", label: "변경 이력" },
        ]}
      />,
    );
    expect(screen.getByRole("tab", { name: "코드" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    fireEvent.click(screen.getByRole("tab", { name: "변경 이력" }));
    expect(change).toHaveBeenCalledWith("history");
  });
  it("작업창은 바깥 클릭에 유지되고 명시 닫기만 실행", () => {
    const close = vi.fn();
    render(
      <Modal title="값 변경" onClose={close}>
        <input aria-label="새 값" defaultValue="10" />
      </Modal>,
    );
    fireEvent.click(screen.getByRole("dialog").parentElement!);
    expect(close).not.toHaveBeenCalled();
    expect(screen.getByLabelText("새 값")).toHaveValue("10");
    fireEvent.click(screen.getByLabelText("닫기"));
    expect(close).toHaveBeenCalledOnce();
  });
  it("위험 동작은 확인창에서 확인 후 호출", () => {
    const confirm = vi.fn();
    render(
      <ConfirmDialog
        message="사용 중지하시겠습니까?"
        onCancel={vi.fn()}
        onConfirm={confirm}
        destructive
      />,
    );
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(confirm).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "확인" }));
    expect(confirm).toHaveBeenCalledOnce();
  });
  it("성공 토스트는 3초 후 사라짐", () => {
    vi.useFakeTimers();
    const dismiss = vi.fn();
    render(<SuccessToast message="저장했습니다." onDismiss={dismiss} />);
    act(() => vi.advanceTimersByTime(2999));
    expect(dismiss).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(1));
    expect(dismiss).toHaveBeenCalledOnce();
    vi.useRealTimers();
  });
  it("로딩/빈 상태/잠김/5색 배지", () => {
    render(
      <>
        <Skeleton />
        <EmptyState
          reason="등록한 항목이 없습니다."
          action="그룹 추가"
          onAction={vi.fn()}
        />
        <LockedBanner>마감되었습니다.</LockedBanner>
        {(["success", "warning", "danger", "info", "locked"] as const).map(
          (tone) => (
            <Badge key={tone} tone={tone}>
              {tone}
            </Badge>
          ),
        )}
      </>,
    );
    expect(screen.getByLabelText("불러오는 중")).toHaveAttribute(
      "aria-busy",
      "true",
    );
    expect(screen.getByText("등록한 항목이 없습니다.")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "그룹 추가" }),
    ).toBeInTheDocument();
    expect(screen.getByText("마감되었습니다.")).toBeInTheDocument();
  });
  it("권한 자리는 모든 제어를 표시하고 ALL", () => {
    render(
      <Can perm="UI-27.delete">
        <button>사용 중지</button>
      </Can>,
    );
    expect(screen.getByRole("button")).toBeInTheDocument();
    expect(useAccess.getState().can("UI-27.update")).toBe(true);
    expect(useAccess.getState().scope).toBe("ALL");
  });
  it("반응형 shell의 모바일 메뉴 토글과 화면 탭 영역", () => {
    render(
      <AppFrame
        header="SI 원가관리"
        navigation={<a href="/UI-27">공통코드</a>}
        screenTabs="대시보드"
      >
        본문
      </AppFrame>,
    );
    expect(screen.getByLabelText("메뉴 열기")).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    fireEvent.click(screen.getByLabelText("메뉴 열기"));
    expect(screen.getByLabelText("메뉴 열기")).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    expect(screen.getByText("대시보드").className).toContain("screenTabs");
  });
});
