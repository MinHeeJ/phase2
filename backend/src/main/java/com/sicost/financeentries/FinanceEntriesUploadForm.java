package com.sicost.financeentries;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.entries.FinanceEntryInput;
import com.sicost.entries.FinanceEntryModels.EntryInput;
import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import com.sicost.uploadsops.UploadModels.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import static com.sicost.financeentries.FinanceEntriesUploadModels.*;

@Component
public class FinanceEntriesUploadForm {
    public static final List<String> HEADERS=List.of("연월","구분","항목","금액","VAT","입출금일","반복","비고");
    private static final List<String> FIELDS=List.of("entry_ym","fin_se_cd","item_nm","entry_amt","vat_amt","inout_dt","rpt_yn","remark");
    private final FinanceEntriesUploadMapper mapper;
    private final ObjectMapper json;
    public FinanceEntriesUploadForm(FinanceEntriesUploadMapper mapper,ObjectMapper json) {this.mapper=mapper;this.json=json;}
    public Parsed parse(Path path) {
        var codes=mapper.codes();var mappings=mapper.mappings();
        List<Preview> rows=new ArrayList<>();List<Issue> issues=new ArrayList<>();
        String month=null;Set<EntryInput> seen=new HashSet<>();
        try(InputStream in=Files.newInputStream(path);var book=new XSSFWorkbook(in)) {
            if(book.isMacroEnabled() || !book.getExternalLinksTable().isEmpty())return failure("매크로·외부 연결은 허용하지 않습니다.");
            if(book.getNumberOfSheets()!=1)return failure("재무 항목 시트 하나만 사용하세요.");
            Sheet sheet=book.getSheetAt(0);Row title=null;
            for(int n=0;n<20 && title==null;n++) {
                Row candidate=sheet.getRow(n);
                if(candidate!=null)for(Cell cell:candidate)if(cell.getCellType()==CellType.STRING && label(cell.getStringCellValue()).equals("연월")) {title=candidate;break;}
            }
            if(title==null)return failure("필수 열 제목을 찾을 수 없습니다.");
            Map<String,Integer> columns=new LinkedHashMap<>();
            for(Cell cell:title) {
                if(cell.getCellType()==CellType.FORMULA)return failure("수식은 허용하지 않습니다.");
                String key=cell.getCellType()==CellType.STRING?label(cell.getStringCellValue()):"";
                if(HEADERS.contains(key) && columns.put(key,cell.getColumnIndex())!=null)return failure("열 제목이 중복됩니다.");
            }
            if(!columns.keySet().containsAll(HEADERS.subList(0,4)))return failure("필수 열(연월·구분·항목·금액)이 없습니다.");
            DataFormatter formatter=new DataFormatter(Locale.ROOT);
            for(int n=title.getRowNum()+1;n<=sheet.getLastRowNum();n++) {
                Row source=sheet.getRow(n);if(source==null)continue;
                Map<String,String> values=new LinkedHashMap<>();boolean formula=false;
                for(Cell cell:source)if(cell.getCellType()==CellType.FORMULA)formula=true;
                for(String key:HEADERS) {
                    Cell cell=columns.containsKey(key)?source.getCell(columns.get(key)):null;
                    String value="";
                    if(cell!=null && cell.getCellType()!=CellType.FORMULA) {
                        if(cell.getCellType()==CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell))value=BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
                        else if(key.equals("입출금일") && cell.getCellType()==CellType.NUMERIC && DateUtil.isCellDateFormatted(cell))value=cell.getLocalDateTimeCellValue().toLocalDate().toString();
                        else value=formatter.formatCellValue(cell).trim();
                    }
                    values.put(key,value);
                }
                if(values.values().stream().allMatch(String::isBlank) && !formula)continue;
                if(rows.size()==10000)return failure("데이터는 10,000행 이하만 올릴 수 있습니다.");
                int line=n+1,start=issues.size();EntryInput entry=null;
                if(formula)issue(issues,line,"파일","수식은 허용하지 않습니다.","ERROR");
                var body=json.createObjectNode();
                for(int c=0;c<HEADERS.size();c++) {
                    String value=values.get(HEADERS.get(c));
                    if(c>=4 && value.isBlank())continue;
                    if(c==3 || c==4) {
                        if(!value.matches("[0-9]+"))issue(issues,line,HEADERS.get(c),"0 이상의 원 단위 정수를 입력하세요.","ERROR");
                        else try {body.put(FIELDS.get(c),Long.parseLong(value));}
                        catch(NumberFormatException e) {issue(issues,line,HEADERS.get(c),"원 단위 정수 범위를 초과했습니다.","ERROR");}
                    } else body.put(FIELDS.get(c),value);
                }
                String category=resolve(values.get("구분"),codes,mappings,line,issues);
                if(category!=null)body.put("fin_se_cd",category);
                if(issues.subList(start,issues.size()).stream().noneMatch(i->i.severity().equals("ERROR"))) {
                    try {
                        entry=FinanceEntryInput.parse(body);
                        if(month==null)month=entry.entry_ym();
                        else if(!month.equals(entry.entry_ym()))issue(issues,line,"연월","한 연월의 항목만 올릴 수 있습니다.","ERROR");
                        if(!seen.add(entry))issue(issues,line,"항목","동일한 행이 중복됩니다.","ERROR");
                    } catch(ApiException e) {
                        int field=FIELDS.indexOf(e.field());issue(issues,line,field<0?"파일":HEADERS.get(field),"입력값을 확인하세요.","ERROR");
                    }
                }
                var added=issues.subList(start,issues.size());
                String severity=added.stream().anyMatch(i->i.severity().equals("ERROR"))?"ERROR":added.isEmpty()?"NORMAL":"WARNING";
                for(int i=start;i<issues.size();i++) {
                    Issue issue=issues.get(i);String raw=values.get(issue.column());
                    if(raw!=null && raw.length()>500)raw=raw.substring(0,500);
                    issues.set(i,new Issue(issue.row(),issue.column(),issue.severity(),issue.message(),raw,null,
                        issue.column().equals("구분") && issue.severity().equals("ERROR")));
                }
                rows.add(new Preview(line,severity,entry));
            }
            if(rows.isEmpty())return failure("검사할 데이터가 없습니다. 빈 파일로 전체 삭제할 수 없습니다.");
        } catch(IOException|org.apache.poi.openxml4j.exceptions.InvalidOperationException e) {return failure("올바른 xlsx 파일만 올릴 수 있습니다.");}
        catch(org.apache.poi.ooxml.POIXMLException|IllegalArgumentException e) {return failure("올바른 xlsx 파일만 올릴 수 있습니다.");}
        return new Parsed(month,List.copyOf(rows),List.copyOf(issues));
    }
    private static String label(String raw) {
        String text=raw.replace("*","").trim();
        return switch(text) {case "부가세"->"VAT";case "세부 항목"->"항목";case "매달 반복"->"반복";default->text;};
    }
    private static String resolve(String raw,List<UploadCode> codes,List<UploadMapping> mappings,int row,List<Issue> issues) {
        var direct=codes.stream().filter(c->c.group().equals("FIN_CATEGORY") && (c.code().equals(raw)||c.name().equals(raw))).map(UploadCode::code).distinct().toList();
        if(direct.size()==1)return direct.get(0);
        var targets=mappings.stream().filter(m->m.group().equals("FIN_CATEGORY") && m.label().equals(raw))
            .map(UploadMapping::code).filter(code->codes.stream().anyMatch(c->c.group().equals("FIN_CATEGORY")&&c.code().equals(code))).distinct().toList();
        if(targets.size()==1) {issue(issues,row,"구분","등록된 코드 대응으로 읽었습니다.","WARNING");return targets.get(0);}
        issue(issues,row,"구분","사용 중인 재무 구분을 입력하거나 코드 대응을 등록하세요.","ERROR");return null;
    }
    static void issue(List<Issue> issues,int row,String column,String message,String severity) {
        issues.add(new Issue(row,column,severity,message,null,null,false));
    }
    private static Parsed failure(String message) {
        return new Parsed(null,List.of(),List.of(new Issue(1,"파일","ERROR",message,null,null,false)));
    }
}
