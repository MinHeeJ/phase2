package com.sicost.businessplan2;

import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import java.io.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import static com.sicost.businessplan2.BusinessPlanUploadModels.*;

@Component
public class BusinessPlanUploadForm {
    public static final List<String> HEADERS=List.of("연도","월","파트","목표매출","목표영업이익","목표가동률");
    private final BusinessPlanUploadMapper mapper;
    public BusinessPlanUploadForm(BusinessPlanUploadMapper mapper) {this.mapper=mapper;}
    public Parsed parse(Path path) {
        List<Preview> rows=new ArrayList<>();List<Issue> issues=new ArrayList<>();
        String year=null;Set<String> seen=new HashSet<>();
        try(InputStream in=Files.newInputStream(path);var book=new XSSFWorkbook(in)) {
            if(book.isMacroEnabled() || !book.getExternalLinksTable().isEmpty()) return failure("매크로·외부 연결은 허용하지 않습니다.");
            if(book.getNumberOfSheets()!=1) return failure("사업계획 시트 하나만 사용하세요.");
            Sheet sheet=book.getSheetAt(0);Row title=null;
            for(int n=0;n<20;n++) {
                Row candidate=sheet.getRow(n);
                if(candidate!=null) for(Cell cell:candidate)
                    if(cell.getCellType()==CellType.STRING && cell.getStringCellValue().replace("*","").trim().equals("연도")) title=candidate;
                if(title!=null) break;
            }
            if(title==null) return failure("필수 열 제목을 찾을 수 없습니다.");
            Map<String,Integer> columns=new LinkedHashMap<>();
            for(Cell cell:title) {
                String label=cell.getCellType()==CellType.STRING?cell.getStringCellValue().replace("*","").trim():"";
                if(HEADERS.contains(label) && columns.put(label,cell.getColumnIndex())!=null) return failure("열 제목이 중복됩니다.");
            }
            if(!columns.keySet().containsAll(HEADERS)) return failure("필수 열이 없습니다.");
            DataFormatter formatter=new DataFormatter(Locale.ROOT);
            for(int n=title.getRowNum()+1;n<=sheet.getLastRowNum();n++) {
                Row source=sheet.getRow(n);if(source==null) continue;
                Map<String,String> values=new LinkedHashMap<>();boolean formula=false;
                for(Cell cell:source) if(cell.getCellType()==CellType.FORMULA) formula=true;
                for(var column:columns.entrySet()) {
                    Cell cell=source.getCell(column.getValue());
                    values.put(column.getKey(),cell==null?"":formatter.formatCellValue(cell).trim());
                }
                if(values.values().stream().allMatch(String::isBlank) && !formula) continue;
                if(rows.size()==10000) return failure("데이터는 10,000행 이하만 올릴 수 있습니다.");
                int line=n+1,start=issues.size();String y=values.get("연도");
                if(formula) error(issues,line,"파일","수식은 허용하지 않습니다.");
                if(!y.matches("[1-9][0-9]{3}")) error(issues,line,"연도","4자리 연도를 입력하세요.");
                else if(year==null) year=y;
                else if(!year.equals(y)) error(issues,line,"연도","한 연도의 계획만 올릴 수 있습니다.");
                String m=values.get("월");String month=null;
                try {int number=Integer.parseInt(m);if(number<1 || number>12) throw new NumberFormatException();
                    month=y+"-"+String.format(Locale.ROOT,"%02d",number);
                } catch(NumberFormatException e) {error(issues,line,"월","1~12월을 입력하세요.");}
                String label=values.get("파트");
                List<Long> matches=label.length()>50 || label.isBlank()?List.of():mapper.parts(label);
                Long part=matches.size()==1?matches.get(0):null;
                if(part==null) error(issues,line,"파트","사용 중인 파트명 또는 ID를 정확히 입력하세요.");
                if(month!=null && part!=null && !seen.add(month+":"+part)) error(issues,line,"파트","연월·파트가 중복됩니다.");
                Long revenue=money(values.get("목표매출"),false,line,"목표매출",issues);
                Long profit=money(values.get("목표영업이익"),true,line,"목표영업이익",issues);
                BigDecimal rate=null;
                try {rate=new BigDecimal(values.get("목표가동률")).setScale(2,RoundingMode.UNNECESSARY);
                    if(rate.signum()<0 || rate.compareTo(BigDecimal.valueOf(100))>0) throw new ArithmeticException();
                } catch(NumberFormatException|ArithmeticException e) {error(issues,line,"목표가동률","0~100, 소수 둘째 자리까지 입력하세요.");}
                Plan plan=issues.size()==start?new Plan(0,month,part,revenue,profit,rate):null;
                rows.add(new Preview(line,plan==null?"ERROR":"NORMAL",plan));
            }
            if(rows.isEmpty()) return failure("검사할 데이터가 없습니다. 빈 파일로 전체 삭제할 수 없습니다.");
        } catch(IOException|org.apache.poi.openxml4j.exceptions.InvalidOperationException e) {return failure("올바른 xlsx 파일만 올릴 수 있습니다.");}
        catch(org.apache.poi.ooxml.POIXMLException|IllegalArgumentException e) {return failure("올바른 xlsx 파일만 올릴 수 있습니다.");}
        return new Parsed(year,List.copyOf(rows),List.copyOf(issues));
    }
    private static Long money(String raw,boolean signed,int row,String column,List<Issue> issues) {
        try {if(!raw.matches(signed?"-?[0-9]+":"[0-9]+")) throw new NumberFormatException();return Long.parseLong(raw);}
        catch(NumberFormatException e) {error(issues,row,column,signed?"원 단위 정수를 입력하세요.":"0 이상의 원 단위 정수를 입력하세요.");return null;}
    }
    static void error(List<Issue> issues,int row,String column,String message) {
        issues.add(new Issue(row,column,"ERROR",message,null,null,false));
    }
    private static Parsed failure(String message) {
        return new Parsed(null,List.of(),List.of(new Issue(1,"파일","ERROR",message,null,null,false)));
    }
}
