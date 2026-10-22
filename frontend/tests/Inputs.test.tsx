import { useState } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi } from "vitest";
import {
  AmountInput,
  DateInput,
  Form,
  MonthInput,
  SelectInput,
  TextArea,
  TextInput,
} from "../src/components/Inputs";

function AmountField() {
  const [value, setValue] = useState<number | null>(0);
  return (
    <>
      <AmountInput label="금액" value={value} onChange={setValue} />
      <output>{value}</output>
    </>
  );
}
describe("공통 입력", () => {
  it("원/공백/쉼표 붙여넣기를 숫자로 저장하고 쉼표 표시", () => {
    render(<AmountField />);
    fireEvent.paste(screen.getByLabelText("금액"), {
      clipboardData: { getData: () => "1,500,000 원" },
    });
    expect(screen.getByRole("status")).toHaveTextContent("1500000");
    expect(screen.getByLabelText("금액")).toHaveValue("1,500,000");
  });
  it.each([
    ["0", "0"],
    ["999999999999", "999,999,999,999"],
  ])("경계 허용 %s", (raw, formatted) => {
    render(<AmountField />);
    fireEvent.change(screen.getByLabelText("금액"), { target: { value: raw } });
    expect(screen.getByLabelText("금액")).toHaveValue(formatted);
  });
  it.each(["1000000000000", "-1", "1.2", "abc"])(
    "잘못된 금액은 마지막 유효값 유지 %s",
    (value) => {
      render(<AmountField />);
      fireEvent.change(screen.getByLabelText("금액"), { target: { value } });
      expect(screen.getByLabelText("금액")).toHaveValue("0");
      expect(screen.getByRole("alert")).toBeInTheDocument();
    },
  );
  it("최대 50자와 카운터", async () => {
    function Field() {
      const [value, setValue] = useState("");
      return (
        <TextInput
          label="이름"
          value={value}
          onChange={setValue}
          maxLength={50}
        />
      );
    }
    render(<Field />);
    await userEvent.type(screen.getByLabelText("이름"), "a".repeat(51));
    expect(screen.getByLabelText("이름")).toHaveValue("a".repeat(50));
    expect(screen.getByText("50/50")).toBeInTheDocument();
  });
  it("blur 뒤 필수 오류 표시", () => {
    render(
      <TextInput
        label="코드"
        value=""
        onChange={vi.fn()}
        required
        requiredMessage="코드를 입력하세요."
      />,
    );
    const input = screen.getByLabelText(/코드/);
    expect(input).not.toHaveAttribute("aria-invalid", "true");
    fireEvent.blur(input);
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByRole("alert")).toHaveTextContent("코드를 입력하세요.");
  });
  it("저장 시 첫 오류로 포커스하고 제출하지 않음", () => {
    const submit = vi.fn();
    render(
      <Form onSubmit={submit}>
        <TextInput
          label="코드"
          value=""
          onChange={vi.fn()}
          required
          error="코드를 입력하세요."
        />
        <TextInput
          label="이름"
          value=""
          onChange={vi.fn()}
          required
          error="이름을 입력하세요."
        />
        <button type="submit">저장</button>
      </Form>,
    );
    fireEvent.click(screen.getByText("저장"));
    expect(screen.getByLabelText(/코드/)).toHaveFocus();
    expect(submit).not.toHaveBeenCalled();
  });
  it("readonly 입력은 수정 불가", () => {
    render(<TextInput label="코드" value="BE" onChange={vi.fn()} readOnly />);
    expect(screen.getByLabelText("코드")).toHaveAttribute("readonly");
  });
  it("여러 줄/날짜/연월/실제 전달 옵션을 제공", () => {
    render(
      <>
        <TextArea label="메모" value="" onChange={vi.fn()} />
        <DateInput label="시작일" value="2026-10-01" onChange={vi.fn()} />
        <MonthInput label="적용월" value="2026-10" onChange={vi.fn()} />
        <SelectInput
          label="대상"
          value=""
          options={[{ value: "BE", label: "백엔드" }]}
          onChange={vi.fn()}
        />
      </>,
    );
    expect(screen.getByLabelText("메모").tagName).toBe("TEXTAREA");
    expect(screen.getByLabelText("시작일")).toHaveAttribute("type", "date");
    expect(screen.getByLabelText("적용월")).toHaveAttribute("type", "month");
    expect(screen.getByRole("option", { name: "백엔드" })).toBeInTheDocument();
  });
});
