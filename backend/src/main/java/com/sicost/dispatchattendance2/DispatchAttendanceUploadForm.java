package com.sicost.dispatchattendance2;

import com.sicost.common.validation.BillingValues;
import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;

@Component
public class DispatchAttendanceUploadForm {
    public static final List<String> HEADERS=List.of("연월","프로젝트ID","관리번호","이름",
        "근무일수","정규 근무시간","초과 근무시간","해당 월 근무일수");
    private final DispatchAttendanceUploadMapper mapper;
    public DispatchAttendanceUploadForm(DispatchAttendanceUploadMapper mapper) { this.mapper=mapper; }

    public Parsed parse(Path path, Scope scope, Project project) {
        List<Preview> rows=new ArrayList<>();
        List<Issue> issues=new ArrayList<>();
        var sources=mapper.sources(scope);
        boolean closed="CLOSED".equals(mapper.monthStatus(scope.month()));
        boolean billed=mapper.confirmedUse(scope);
        try (InputStream in=Files.newInputStream(path); var book=new XSSFWorkbook(in)) {
            if (book.isMacroEnabled() || !book.getExternalLinksTable().isEmpty())
                return failure("외부 연결 또는 매크로가 있는 파일은 올릴 수 없습니다.");
            if (book.getNumberOfSheets()==0) return failure("첫 시트가 없습니다.");
            Sheet sheet=book.getSheetAt(0);
            Row title=null;
            for (int n=0;n<20;n++) {
                Row candidate=sheet.getRow(n);
                if (candidate!=null) for (Cell cell:candidate)
                    if (cell.getCellType()==CellType.STRING && label(cell).equals("연월")) title=candidate;
                if (title!=null) break;
            }
            if (title==null) return failure("제목 줄을 찾을 수 없습니다.");
            Map<String,Integer> columns=new LinkedHashMap<>();
            for (Cell cell:title) {
                String heading=label(cell);
                if (HEADERS.contains(heading) && columns.put(heading,cell.getColumnIndex())!=null)
                    return failure("중복된 제목이 있습니다.");
            }
            if (!columns.keySet().containsAll(HEADERS)) return failure("필수 열이 없습니다.");
            Map<Long,Integer> seen=new HashMap<>();
            DataFormatter formatter=new DataFormatter(Locale.ROOT);

            for (int n=title.getRowNum()+1;n<=sheet.getLastRowNum();n++) {
                Row row=sheet.getRow(n);
                if (row==null) continue;
                Map<String,String> cells=new LinkedHashMap<>();
                boolean formula=false;
                for (var column:columns.entrySet()) {
                    Cell cell=row.getCell(column.getValue());
                    if (cell!=null && cell.getCellType()==CellType.FORMULA) formula=true;
                    cells.put(column.getKey(),cell==null?"":formatter.formatCellValue(cell).trim());
                }
                if (cells.values().stream().allMatch(String::isBlank)) continue;
                if (rows.size()==10000) return failure("데이터는 10,000행 이하만 올릴 수 있습니다.");
                int line=n+1, start=issues.size();
                if (formula) error(issues,line,"파일","수식이 있는 데이터는 올릴 수 없습니다.");
                if (!scope.month().equals(cells.get("연월"))) error(issues,line,"연월","선택한 월과 다릅니다.");
                try {
                    if (Long.parseLong(cells.get("프로젝트ID"))!=scope.projectId())
                        error(issues,line,"프로젝트ID","선택한 프로젝트와 다릅니다.");
                } catch (NumberFormatException e) { error(issues,line,"프로젝트ID","프로젝트ID를 확인하세요."); }
                if (project==null || !"DP".equals(project.cntrct_ty_cd())
                        || !Set.of("DAY","HOUR").contains(Objects.toString(project.stlm_mthd_cd(),"")))
                    error(issues,line,"프로젝트ID","DP DAY/HOUR 프로젝트만 사용할 수 있습니다.");
                if (project!=null && !"Y".equals(project.use_yn())) error(issues,line,"프로젝트ID","사용 중지 프로젝트입니다.");
                if (closed) error(issues,line,"연월","마감된 달입니다.");
                if (billed) error(issues,line,"연월","확정 청구에 연결된 근태는 교체할 수 없습니다.");
                String number=cells.get("관리번호");
                if (!number.matches("[A-Z0-9-]{1,20}")) error(issues,line,"관리번호","관리번호 형식을 확인하세요.");
                var matches=sources.stream().filter(s->s.mng_no().equals(number)).toList();
                Source source=matches.size()==1?matches.get(0):null;
                if (matches.isEmpty()) error(issues,line,"관리번호","해당 월 유효 배정이 없습니다.");
                if (matches.size()>1) error(issues,line,"관리번호","같은 월의 배정이 여러 건입니다. 배정을 명확히 해 주세요.");
                if (source!=null) {
                    if (!source.dev_nm().equals(cells.get("이름"))) error(issues,line,"이름","관리번호의 이름과 다릅니다.");
                    Integer first=seen.putIfAbsent(source.dev_id(),line);
                    if (first!=null) error(issues,line,"관리번호",first+"행과 프로젝트·개발자·월이 중복됩니다.");
                }
                boolean day=project!=null && "DAY".equals(project.stlm_mthd_cd());
                BigDecimal worked=quantity(cells.get("근무일수"),day,999.9,line,"근무일수",issues);
                BigDecimal regular=quantity(cells.get("정규 근무시간"),!day,9999.9,line,"정규 근무시간",issues);
                BigDecimal overtime=quantity(cells.get("초과 근무시간"),false,9999.9,line,"초과 근무시간",issues);
                if (overtime==null && cells.get("초과 근무시간").isBlank()) overtime=BigDecimal.ZERO.setScale(1);
                BigDecimal denominator=quantity(cells.get("해당 월 근무일수"),true,
                    scope.lastDay().getDayOfMonth(),line,"해당 월 근무일수",issues);
                if (denominator!=null && denominator.signum()==0) error(issues,line,"해당 월 근무일수","월 근무일수는 0보다 커야 합니다.");
                Attendance attendance=null;
                if (issues.subList(start,issues.size()).stream().noneMatch(i->i.severity().equals("ERROR")) && source!=null) {
                    List<String> warnings=new ArrayList<>();
                    BigDecimal mm=worked==null?null:worked.divide(denominator,2,RoundingMode.HALF_UP);
                    if (mm==null) warnings.add("근무일수가 없어 산출 MM을 계산하지 않습니다.");
                    if (!source.price_available()) warnings.add("청구 단가가 없습니다. 근태 반영은 가능합니다.");
                    if (mm!=null && source.assignment_mm()!=null && mm.compareTo(source.assignment_mm())!=0)
                        warnings.add("산출 MM과 ⑦ 투입 MM이 다릅니다. 원본 MM은 변경하지 않습니다.");
                    for (String warning:warnings) issues.add(new Issue(line,"근태","WARNING",warning,null,null,false));
                    attendance=new Attendance(source.asgn_id(),source.dev_id(),worked,regular,overtime,denominator,
                        mm,source.assignment_mm(),source.price_available(),List.copyOf(warnings));
                }
                String severity=attendance==null?"ERROR":issues.size()>start?"WARNING":"NORMAL";
                rows.add(new Preview(line,severity,attendance));
            }
            if (rows.isEmpty()) return failure("검사할 데이터가 없습니다. 빈 파일로 전체 삭제할 수 없습니다.");
            return new Parsed(List.copyOf(rows),List.copyOf(issues));
        } catch (IOException | RuntimeException e) { return failure("올바른 xlsx 파일만 올릴 수 있습니다."); }
    }
    private static String label(Cell cell) {
        return cell.getCellType()==CellType.STRING?cell.getStringCellValue().replace("*","").trim():"";
    }
    private static BigDecimal quantity(String raw, boolean required, double max, int line,
            String column, List<Issue> issues) {
        if (raw.isBlank()) {
            if (required) error(issues,line,column,"필수 값입니다.");
            return null;
        }
        try {
            BigDecimal value=new BigDecimal(raw).setScale(1,RoundingMode.UNNECESSARY);
            if (value.signum()<0 || value.compareTo(BigDecimal.valueOf(max))>0) throw new ArithmeticException();
            return value;
        } catch (NumberFormatException | ArithmeticException e) {
            error(issues,line,column,"0 이상, 허용 범위 내 소수 첫째 자리까지 입력하세요.");
            return null;
        }
    }
    private static void error(List<Issue> issues, int row, String column, String message) {
        issues.add(new Issue(row,column,"ERROR",message,null,null,false));
    }
    private static Parsed failure(String message) {
        return new Parsed(List.of(),List.of(new Issue(1,"파일","ERROR",message,null,null,false)));
    }
}
