package com.sicost.dispatchattendance2;

import com.sicost.common.api.ApiException;
import com.sicost.common.billing.BillingRules;
import com.sicost.common.validation.DeveloperManagementNumbers;
import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import java.io.*;
import java.math.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;

@Component
public class DispatchAttendanceUploadForm {
    public static final List<String> HEADERS = com.sicost.uploadsops.PersonnelUploadForm.ATTENDANCE_HEADERS;
    public Parsed parse(InputStream input, Scope scope, Project project, List<Assignment> assignments) {
        try (var book = new XSSFWorkbook(input)) {
            if (book.isMacroEnabled() || !book.getExternalLinksTable().isEmpty() || book.getNumberOfSheets()==0) throw invalid("외부 연결·매크로 없는 xlsx 파일을 선택하세요.");
            Sheet sheet=book.getSheetAt(0); Row title=null; Map<String,Integer> columns=new LinkedHashMap<>();
            for (int n=0;n<20;n++) {
                Row candidate=sheet.getRow(n); if(candidate==null) continue;
                Map<String,Integer> found=new LinkedHashMap<>();
                for(Cell c:candidate) if(c.getCellType()==CellType.STRING) {
                    String h=c.getStringCellValue().replace("*","").trim();
                    if(HEADERS.contains(h) && found.put(h,c.getColumnIndex())!=null) throw invalid("중복된 제목이 있습니다.");
                }
                if(found.containsKey("관리번호")){title=candidate;columns=found;break;}
            }
            if(title==null || !columns.keySet().containsAll(HEADERS)) throw invalid("B.3 필수 제목 8개를 확인하세요.");
            List<Preview> rows=new ArrayList<>(); List<Issue> issues=new ArrayList<>(); Set<Long> seen=new HashSet<>();
            DataFormatter formatter=new DataFormatter(Locale.ROOT);
            for(int n=title.getRowNum()+1;n<=sheet.getLastRowNum();n++) {
                Row row=sheet.getRow(n); if(row==null) continue;
                Map<String,String> v=new LinkedHashMap<>(); boolean formula=false;
                for(var c:columns.entrySet()) {Cell cell=row.getCell(c.getValue()); if(cell!=null&&cell.getCellType()==CellType.FORMULA) formula=true;
                    v.put(c.getKey(),cell==null?"":formatter.formatCellValue(cell).trim());}
                if(v.values().stream().allMatch(String::isBlank)||v.get("이름").startsWith("예시)"))continue;
                if(rows.size()>=10000)throw invalid("데이터는 10,000행 이하만 올릴 수 있습니다.");
                int line=n+1,before=issues.size(); Attendance attendance=null;BigDecimal mm=null,source=null;List<String> warnings=new ArrayList<>();
                try {
                    if(formula)throw invalid("수식은 사용할 수 없습니다.");
                    if(!scope.month().equals(v.get("연월")))throw BillingRules.invalid("연월");
                    if(!v.get("프로젝트ID").matches("[1-9][0-9]*")||Long.parseLong(v.get("프로젝트ID"))!=scope.projectId())throw BillingRules.invalid("프로젝트ID");
                    String number=DeveloperManagementNumbers.supplied(v.get("관리번호"));
                    if(number==null)throw BillingRules.invalid("관리번호");
                    var matched=assignments.stream().filter(a->a.mng_no().equals(number)).toList();
                    if(matched.size()!=1)throw new ApiException(400,"VALIDATION_ERROR","유효 배정을 하나로 확인할 수 없습니다.","관리번호");
                    var a=matched.get(0);
                    if(!a.dev_nm().equals(v.get("이름")))throw BillingRules.invalid("이름");
                    if(!seen.add(a.dev_id()))throw new ApiException(400,"VALIDATION_ERROR","파일 내 프로젝트·개발자·월이 중복됩니다.","관리번호");
                    BigDecimal day=quantity(v,"근무일수",5,"DAY".equals(project.basis()));
                    BigDecimal regular=quantity(v,"정규 근무시간",6,"HOUR".equals(project.basis()));
                    BigDecimal overtime=v.get("초과 근무시간").isBlank()?BigDecimal.ZERO:quantity(v,"초과 근무시간",6,false);
                    BigDecimal denominator=quantity(v,"해당 월 근무일수",5,true);
                    if(denominator.signum()<=0 || denominator.compareTo(BigDecimal.valueOf(scope.last().getDayOfMonth()))>0)throw BillingRules.invalid("해당 월 근무일수");
                    if(day!=null)mm=day.divide(denominator,2,RoundingMode.HALF_UP);
                    else warnings.add("근무일수가 없어 산출 MM을 계산하지 않습니다.");
                    if(!a.price_available())warnings.add("단가가 없어 자동 청구를 생성할 수 없습니다.");
                    source=a.assignment_mm();
                    if(mm!=null&&source!=null&&mm.compareTo(source)!=0)warnings.add("산출 MM과 투입 MM이 다릅니다. 원본 투입 MM은 변경하지 않습니다.");
                    attendance=new Attendance(null,a.asgn_id(),a.dev_id(),day,regular,overtime,denominator);
                    for(String message:warnings)issues.add(new Issue(line,"근무일수","WARNING",message,null,null,false));
                } catch(ApiException e) {issues.add(new Issue(line,e.field()==null?"파일":e.field(),"ERROR",e.getMessage(),v.get(e.field()),null,false));}
                  catch(NumberFormatException e) {issues.add(new Issue(line,"프로젝트ID","ERROR","올바른 프로젝트ID를 입력하세요.",v.get("프로젝트ID"),null,false));}
                boolean error=issues.subList(before,issues.size()).stream().anyMatch(i->i.severity().equals("ERROR"));
                rows.add(new Preview(line,error?"ERROR":warnings.isEmpty()?"NORMAL":"WARNING",attendance,mm,source,List.copyOf(warnings)));
            }
            if(rows.isEmpty())throw invalid("검사할 데이터가 없습니다.");
            return new Parsed(List.copyOf(rows),List.copyOf(issues));
        } catch(ApiException e){throw e;} catch(IOException|RuntimeException e){throw invalid("올바른 xlsx 파일만 올릴 수 있습니다.");}
    }
    private BigDecimal quantity(Map<String,String> v,String field,int precision,boolean required) {
        String raw=v.get(field);if(raw.isBlank()){if(required)throw BillingRules.invalid(field);return null;}
        try{return BillingRules.quantity(new BigDecimal(raw),precision,1,field);}catch(NumberFormatException e){throw BillingRules.invalid(field);}
    }
    private static ApiException invalid(String message){return new ApiException(400,"VALIDATION_ERROR",message,"file");}
}
