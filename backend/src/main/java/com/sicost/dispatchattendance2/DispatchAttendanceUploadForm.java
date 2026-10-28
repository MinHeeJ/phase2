package com.sicost.dispatchattendance2;

import com.sicost.common.api.ApiException;
import com.sicost.common.validation.BillingValues;
import com.sicost.common.validation.DeveloperManagementNumbers;
import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import java.io.*;
import java.math.*;
import java.time.YearMonth;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;

@Component
public class DispatchAttendanceUploadForm {
    public static final List<String> HEADERS = List.of("연월", "프로젝트ID", "관리번호", "이름",
        "근무일수", "정규 근무시간", "초과 근무시간", "해당 월 근무일수");

    public Parsed parse(InputStream input, Scope scope, Project project, List<Assignment> assignments) {
        try (var book = new XSSFWorkbook(input)) {
            if (book.isMacroEnabled() || !book.getExternalLinksTable().isEmpty()) throw fileError();
            Sheet sheet = book.getNumberOfSheets() == 0 ? null : book.getSheetAt(0);
            Row title = title(sheet);
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (Cell cell : title) {
                String label = text(cell).replace("*", "").trim();
                if (HEADERS.contains(label) && columns.put(label, cell.getColumnIndex()) != null) {
                    throw BillingValues.invalid("file");
                }
            }
            if (!columns.keySet().containsAll(HEADERS)) throw BillingValues.invalid("file");
            List<Preview> rows = new ArrayList<>();
            List<Issue> issues = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            for (Row row : sheet) {
                if (row.getRowNum() <= title.getRowNum()) continue;
                Map<String, String> values = new LinkedHashMap<>();
                columns.forEach((key, column) -> values.put(key, text(row.getCell(column))));
                boolean formula = false;
                for (Cell cell : row) if (cell.getCellType() == CellType.FORMULA) formula = true;
                if (!formula && (values.values().stream().allMatch(String::isBlank)
                        || values.get("이름").startsWith("예시)"))) continue;
                if (rows.size() >= 10000) throw new ApiException(400, "VALIDATION_ERROR",
                    "데이터는 10,000행 이하만 올릴 수 있습니다.", "file");
                int line = row.getRowNum() + 1;
                int start = issues.size();
                if (formula) error(issues, line, "파일", "", "수식은 허용하지 않습니다.");
                if (!scope.month().equals(values.get("연월"))) {
                    error(issues, line, "연월", values.get("연월"), "선택한 연월과 다릅니다.");
                }
                if (!project.number().equals(values.get("프로젝트ID"))
                        && !Long.toString(scope.projectId()).equals(values.get("프로젝트ID"))) {
                    error(issues, line, "프로젝트ID", values.get("프로젝트ID"), "선택한 프로젝트와 다릅니다.");
                }
                String number = values.get("관리번호");
                try {
                    if (number.isBlank()) throw BillingValues.invalid("관리번호");
                    DeveloperManagementNumbers.supplied(number);
                } catch (ApiException ex) {
                    error(issues, line, "관리번호", number, "관리번호 형식을 확인하세요.");
                }
                List<Assignment> matches = assignments.stream().filter(a -> a.mng_no().equals(number)).toList();
                Assignment assignment = matches.size() == 1 ? matches.get(0) : null;
                if (assignment == null) error(issues, line, "관리번호", number,
                    "선택 월의 유효 배정이 없거나 여러 건입니다. 배정을 확인하세요.");
                else {
                    if (!assignment.dev_nm().equals(values.get("이름"))) {
                        error(issues, line, "이름", values.get("이름"), "관리번호의 이름과 다릅니다.");
                    }
                    if (!seen.add(assignment.dev_id())) error(issues, line, "관리번호", number,
                        "파일 안에 프로젝트·개발자·월이 중복됩니다. 파일 전체를 반영하지 않습니다.");
                }
                BigDecimal days = decimal(values, "근무일수", "DAY".equals(project.settlement()), line, issues);
                BigDecimal hours = decimal(values, "정규 근무시간", "HOUR".equals(project.settlement()), line, issues);
                BigDecimal overtime = decimal(values, "초과 근무시간", false, line, issues);
                if (values.get("초과 근무시간").isBlank()) overtime = BigDecimal.ZERO;
                BigDecimal denominator = decimal(values, "해당 월 근무일수", true, line, issues);
                if (denominator != null && (denominator.signum() <= 0
                        || denominator.compareTo(BigDecimal.valueOf(YearMonth.parse(scope.month()).lengthOfMonth())) > 0)) {
                    error(issues, line, "해당 월 근무일수", values.get("해당 월 근무일수"),
                        "0보다 크고 해당 월 달력 일수 이하여야 합니다.");
                }
                BigDecimal mm = days != null && denominator != null && denominator.signum() > 0
                    ? days.divide(denominator, 2, RoundingMode.HALF_UP) : null;
                if (assignment != null && assignment.bill_price() == null) {
                    warning(issues, line, "관리번호", number, "단가가 없습니다. 근태 반영은 가능하나 청구 생성은 불가합니다.");
                }
                if (assignment != null && mm != null && assignment.assignment_mm() != null
                        && mm.compareTo(assignment.assignment_mm()) != 0) {
                    warning(issues, line, "근무일수", values.get("근무일수"), "산출 MM과 투입 MM이 다릅니다. 원본 투입 MM은 변경하지 않습니다.");
                }
                String severity = issues.subList(start, issues.size()).stream().anyMatch(i -> i.severity().equals("ERROR"))
                    ? "ERROR" : issues.size() > start ? "WARNING" : "NORMAL";
                Attendance attendance = assignment == null ? null : new Attendance(null, assignment.asgn_id(),
                    assignment.dev_id(), days, hours, overtime, denominator);
                rows.add(new Preview(line, severity, number, values.get("이름"), attendance, mm,
                    assignment == null ? null : assignment.assignment_mm(), assignment != null && assignment.bill_price() != null));
            }
            if (rows.isEmpty()) throw new ApiException(400, "VALIDATION_ERROR", "검사할 데이터가 없습니다.", "file");
            return new Parsed(List.copyOf(rows), List.copyOf(issues));
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw fileError();
        }
    }

    private static Row title(Sheet sheet) {
        if (sheet != null) for (int i = 0; i < 20; i++) {
            Row row = sheet.getRow(i);
            if (row != null) for (Cell cell : row) {
                if ("연월".equals(text(cell).replace("*", "").trim())) return row;
            }
        }
        throw new ApiException(400, "VALIDATION_ERROR", "제목 줄을 찾을 수 없습니다.", "file");
    }
    private static String text(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BLANK -> "";
            default -> "#INVALID";
        };
    }
    private static BigDecimal decimal(Map<String, String> values, String column, boolean required,
            int row, List<Issue> issues) {
        String raw = values.get(column);
        if (raw.isBlank() && !required) return null;
        try {
            BigDecimal value = new BigDecimal(raw).setScale(1, RoundingMode.UNNECESSARY);
            int precision = column.contains("시간") ? 6 : 5;
            if (value.signum() < 0 || value.precision() > precision) throw new NumberFormatException();
            return value;
        } catch (ArithmeticException | NumberFormatException ex) {
            error(issues, row, column, raw, "0 이상의 소수 첫째 자리까지 입력하세요. 필수값은 비울 수 없습니다.");
            return null;
        }
    }
    private static void error(List<Issue> issues, int row, String column, String raw, String message) {
        issues.add(new Issue(row, column, "ERROR", message, raw, null, false));
    }
    private static void warning(List<Issue> issues, int row, String column, String raw, String message) {
        issues.add(new Issue(row, column, "WARNING", message, raw, null, false));
    }
    private static ApiException fileError() {
        return new ApiException(400, "VALIDATION_ERROR", "외부 연결·매크로가 없는 올바른 xlsx 파일만 올릴 수 있습니다.", "file");
    }
}
