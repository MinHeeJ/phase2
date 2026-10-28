package com.sicost.dispatchattendance2;

import com.sicost.common.api.ApiException;
import com.sicost.common.validation.BillingRules;
import com.sicost.common.validation.DeveloperManagementNumbers;
import com.sicost.uploadsops.PersonnelUploadForm;
import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;

@Component
public class DispatchAttendanceUploadForm {
    private final DispatchAttendanceUploadMapper mapper;
    public DispatchAttendanceUploadForm(DispatchAttendanceUploadMapper mapper) { this.mapper = mapper; }

    static ApiException fileError(String code, String message) {
        return new ApiException(400, code, message, "file");
    }
    public Parsed parse(InputStream input, Scope scope, Project project) throws IOException {
        XSSFWorkbook workbook;
        try { workbook = new XSSFWorkbook(input); }
        catch (IOException | RuntimeException ex) {
            throw fileError("UNSUPPORTED_FILE_TYPE", "올바른 xlsx 파일만 올릴 수 있습니다.");
        }
        try (var book = workbook) {
            if (book.isMacroEnabled() || !book.getExternalLinksTable().isEmpty()) {
                throw fileError("UNSUPPORTED_FILE_TYPE", "외부 연결 또는 매크로가 있는 파일은 올릴 수 없습니다.");
            }
            if (book.getNumberOfSheets() == 0) throw fileError("HEADER_NOT_FOUND", "첫 시트가 없습니다.");
            Sheet sheet = book.getSheetAt(0);
            Row title = PersonnelUploadForm.title(sheet, "B.3");
            var headers = PersonnelUploadForm.ATTENDANCE_HEADERS;
            Map<String,Integer> columns = new LinkedHashMap<>();
            for (Cell cell : title) {
                if (cell.getCellType() != CellType.STRING) continue;
                String label = PersonnelUploadForm.label(cell.getStringCellValue(), "B.3");
                if (headers.contains(label) && columns.put(label, cell.getColumnIndex()) != null) {
                    throw fileError("REQUIRED_COLUMN_MISSING", "중복된 제목이 있습니다.");
                }
            }
            if (!columns.keySet().containsAll(headers)) {
                throw fileError("REQUIRED_COLUMN_MISSING", "필수 열 8개를 모두 유지해 주세요. 해당 월 근무일수도 필요합니다.");
            }
            List<Source> sources = mapper.sources(scope);
            List<Preview> rows = new ArrayList<>();
            List<Issue> issues = new ArrayList<>();
            List<Attendance> attendance = new ArrayList<>();
            Map<Long,Integer> seen = new HashMap<>();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (int index = title.getRowNum() + 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null) continue;
                boolean formula = false;
                for (Cell cell : row) if (cell.getCellType() == CellType.FORMULA) formula = true;
                Map<String,String> values = new LinkedHashMap<>();
                for (var entry : columns.entrySet()) {
                    Cell cell = row.getCell(entry.getValue());
                    values.put(entry.getKey(), cell == null || cell.getCellType() == CellType.FORMULA
                        ? "" : formatter.formatCellValue(cell).trim());
                }
                if (!formula && values.values().stream().allMatch(String::isBlank)) continue;
                if (rows.size() >= 10000) throw fileError("TOO_MANY_ROWS", "데이터는 10,000행 이하만 올릴 수 있습니다.");
                int line = index + 1;
                int start = issues.size();
                Map<String,Object> normalized = new LinkedHashMap<>();
                if (formula) error(issues, line, "파일", "", "수식이 있는 데이터는 올릴 수 없습니다.");
                if (!scope.month().equals(values.get("연월"))) error(issues, line, "연월", values.get("연월"), "선택한 연월과 다릅니다.");
                if (!Long.toString(scope.projectId()).equals(values.get("프로젝트ID"))) error(issues, line, "프로젝트ID", values.get("프로젝트ID"), "선택한 프로젝트와 다릅니다.");
                String number = values.get("관리번호");
                try {
                    if (DeveloperManagementNumbers.supplied(number) == null) throw BillingRules.invalid("mng_no");
                } catch (ApiException ex) { error(issues, line, "관리번호", number, "올바른 관리번호를 입력하세요."); }
                List<Source> matching = sources.stream().filter(s -> s.mng_no().equals(number)).toList();
                Source source = matching.size() == 1 ? matching.get(0) : null;
                if (source == null) error(issues, line, "관리번호", number, "이 달에 유효한 배정이 없거나 배정이 여러 건입니다. 투입을 확인하세요.");
                else {
                    Integer first = seen.putIfAbsent(source.dev_id(), line);
                    if (first != null) error(issues, line, "관리번호", number, "파일 안에서 " + first + "행과 프로젝트·개발자·월이 중복됩니다.");
                    if (!source.dev_nm().equals(values.get("이름"))) error(issues, line, "이름", values.get("이름"), "관리번호와 이름이 일치하지 않습니다.");
                }
                BigDecimal days = decimal(values, "근무일수", "DAY".equals(project.settlement()), 5, line, issues);
                BigDecimal regular = decimal(values, "정규 근무시간", "HOUR".equals(project.settlement()), 6, line, issues);
                BigDecimal overtime = decimal(values, "초과 근무시간", false, 6, line, issues);
                if (overtime == null && values.get("초과 근무시간").isBlank()) overtime = BigDecimal.ZERO.setScale(1);
                BigDecimal denominator = decimal(values, "해당 월 근무일수", true, 5, line, issues);
                if (denominator != null && (denominator.signum() <= 0
                    || denominator.compareTo(BigDecimal.valueOf(YearMonth.parse(scope.month()).lengthOfMonth())) > 0)) {
                    error(issues, line, "해당 월 근무일수", values.get("해당 월 근무일수"), "0 초과이며 해당 월의 달력 일수 이하여야 합니다.");
                }
                BigDecimal mm = null;
                boolean invalid = issues.subList(start, issues.size()).stream().anyMatch(i -> i.severity().equals("ERROR"));
                if (!invalid) {
                    if (days != null) {
                        try { mm = BillingRules.attendanceMm(days, denominator); }
                        catch (ApiException ex) { error(issues, line, "근무일수", values.get("근무일수"), "산출 MM의 허용 범위를 초과했습니다."); }
                    } else warning(issues, line, "근무일수", "", "근무일수가 없어 산출 MM은 비어 있습니다.");
                    if (source != null) {
                        if (!source.price_available()) warning(issues, line, "관리번호", number, "청구 단가가 없습니다. 근태 저장은 가능하지만 청구 전 단가를 확인하세요.");
                        if (mm != null && source.assignment_mm() != null && mm.compareTo(source.assignment_mm()) != 0) {
                            warning(issues, line, "근무일수", values.get("근무일수"), "산출 MM과 투입 MM이 다릅니다. 원본 투입 MM은 변경하지 않습니다.");
                        }
                    }
                }
                boolean error = issues.subList(start, issues.size()).stream().anyMatch(i -> i.severity().equals("ERROR"));
                String severity = error ? "ERROR" : issues.size() > start ? "WARNING" : "NORMAL";
                normalized.put("mng_no", number);
                normalized.put("dev_nm", values.get("이름"));
                normalized.put("work_ym", scope.month());
                normalized.put("prj_id", scope.projectId());
                normalized.put("asgn_id", source == null ? null : source.asgn_id());
                normalized.put("dev_id", source == null ? null : source.dev_id());
                normalized.put("work_day_qty", days);
                normalized.put("rglr_tm_qty", regular);
                normalized.put("ovtm_tm_qty", overtime);
                normalized.put("mth_work_day_qty", denominator);
                normalized.put("calculated_mm", mm);
                normalized.put("assignment_mm", source == null ? null : source.assignment_mm());
                normalized.put("price_available", source != null && source.price_available());
                rows.add(new Preview(line, severity, Collections.unmodifiableMap(normalized)));
                if (!error && source != null) attendance.add(new Attendance(null, scope.month(), scope.projectId(),
                    source.dev_id(), source.asgn_id(), days, regular, overtime, denominator, null));
            }
            if (rows.isEmpty()) throw fileError("EMPTY_FILE", "검사할 데이터가 없습니다.");
            return new Parsed(List.copyOf(rows), List.copyOf(issues), List.copyOf(attendance));
        }
    }
    private static BigDecimal decimal(Map<String,String> values, String column, boolean required,
                                      int precision, int row, List<Issue> issues) {
        String raw = values.get(column);
        if (raw.isBlank()) {
            if (required) error(issues, row, column, raw, column + "는 필수입니다.");
            return null;
        }
        try {
            BigDecimal result = new BigDecimal(raw.replace(",", "")).setScale(1, RoundingMode.UNNECESSARY);
            if (result.signum() < 0 || result.precision() > precision) throw new ArithmeticException();
            return result;
        } catch (NumberFormatException | ArithmeticException ex) {
            error(issues, row, column, raw, "0 이상이며 소수 첫째 자리까지 허용합니다.");
            return null;
        }
    }
    private static void error(List<Issue> issues, int row, String column, String raw, String message) {
        issues.add(new Issue(row, column, "ERROR", message, raw, null, false));
    }
    private static void warning(List<Issue> issues, int row, String column, String raw, String message) {
        issues.add(new Issue(row, column, "WARNING", message, raw, null, false));
    }
}
