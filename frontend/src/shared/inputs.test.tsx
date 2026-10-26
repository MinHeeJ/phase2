import { render, screen, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import {
  Form,
  MoneyInput,
  TextInput,
  DateInput,
  MonthInput,
  SelectInput,
  TextArea,
} from "./inputs";
import styles from "./ui.module.css";

function MoneyForm({
  initial = "",
  save = () => undefined,
}: {
  initial?: string;
  save?: () => void;
}) {
  const [value, setValue] = useState(initial);
  return (
    <Form onSave={save}>
      <MoneyInput
        label="금액"
        name="amount"
        value={value}
        onChange={setValue}
        required
      />
      <button type="submit">저장</button>
    </Form>
  );
}
function NameForm() {
  const [value, setValue] = useState("");
  return (
    <TextInput
      label="이름"
      name="name"
      value={value}
      maxLength={50}
      required
      onChange={(event) => setValue(event.target.value)}
    />
  );
}
describe("공통 입력", () => {
  it("붙여넣기 정규화 후 쉼표와 우측 정렬을 유지한다", () => {
    render(<MoneyForm />);
    const input = screen.getByLabelText(/금액/);
    fireEvent.change(input, { target: { value: "1,500,000 원" } });
    expect(input).toHaveValue("1,500,000");
    expect(input).toHaveClass(styles.numeric);
  });
  it("최솟값과 최댓값을 저장할 수 있다", () => {
    const save = vi.fn();
    render(<MoneyForm initial="0" save={save} />);
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(save).toHaveBeenCalledTimes(1);
    fireEvent.change(screen.getByLabelText(/금액/), {
      target: { value: "999999999999" },
    });
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(save).toHaveBeenCalledTimes(2);
  });
  it("범위 초과 값은 남겨 두고 저장하지 않으며 첫 오류에 포커스한다", () => {
    const save = vi.fn();
    render(<MoneyForm save={save} />);
    const input = screen.getByLabelText(/금액/);
    fireEvent.change(input, { target: { value: "1000000000000" } });
    fireEvent.blur(input);
    fireEvent.click(screen.getByRole("button", { name: "저장" }));
    expect(input).toHaveValue("1000000000000");
    expect(input).toHaveFocus();
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(save).not.toHaveBeenCalled();
    expect(screen.getByText(/범위의 정수/)).toBeVisible();
  });
  it("음수와 소수는 조용히 유효한 값으로 바꾸지 않는다", () => {
    render(<MoneyForm />);
    const input = screen.getByLabelText(/금액/);
    fireEvent.change(input, { target: { value: "-1.5" } });
    fireEvent.blur(input);
    expect(input).toHaveValue("-1.5");
    expect(input).toHaveAttribute("aria-invalid", "true");
  });
  it("50번째 글자까지 허용하고 51번째는 차단한다", async () => {
    const user = userEvent.setup();
    render(<NameForm />);
    await user.type(screen.getByLabelText(/이름/), "가".repeat(51));
    expect(screen.getByLabelText(/이름/)).toHaveValue("가".repeat(50));
    expect(screen.getByText("50/50")).toBeVisible();
  });
  it("blur 전에는 필수 오류를 표시하지 않는다", () => {
    render(<NameForm />);
    const input = screen.getByLabelText(/이름/);
    expect(input).toHaveAttribute("aria-invalid", "false");
    fireEvent.blur(input);
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByText("필수 항목을 입력하세요.")).toBeVisible();
  });
  it("서버 필드 오류와 편집값을 함께 표시한다", () => {
    render(
      <TextInput
        name="name"
        label="이름"
        value="입력 유지"
        error="이미 있는 이름입니다."
        readOnly
      />,
    );
    expect(screen.getByLabelText("이름")).toHaveValue("입력 유지");
    expect(screen.getByText("이미 있는 이름입니다.")).toBeVisible();
  });
  it("날짜 연월 선택 여러 줄 입력은 의미있는 HTML 입력이다", () => {
    render(
      <>
        <DateInput name="date" label="날짜" />
        <MonthInput name="month" label="연월" />
        <SelectInput name="choice" label="선택">
          <option value="">선택</option>
        </SelectInput>
        <TextArea name="memo" label="메모" maxLength={200} />
      </>,
    );
    expect(screen.getByLabelText("날짜")).toHaveAttribute("type", "date");
    expect(screen.getByLabelText("연월")).toHaveAttribute("type", "month");
    expect(screen.getByLabelText("선택")).toHaveRole("combobox");
    expect(screen.getByLabelText("메모").tagName).toBe("TEXTAREA");
  });
});
