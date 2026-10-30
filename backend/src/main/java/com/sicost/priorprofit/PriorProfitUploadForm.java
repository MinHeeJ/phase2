package com.sicost.priorprofit;


import com.sicost.uploadsops.UploadModels.*;
import com.sicost.uploadsops.PersonnelUploadModels.Issue;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import static com.sicost.priorprofit.PriorProfitUploadModels.*;

@Component
public class PriorProfitUploadForm {
    public static final List<String> HEADERS=List.of("연월","매출액","배분 원가","대기 원가","직접 경비",
        "판관비 입력 항목","간접 인건비","연구개발비","전월 조정","영업외수익","영업외비용","법인세","배분 기준 매출");
    public static final List<String> DETAIL_HEADERS=List.of("연월","항목","금액");
    private final PriorProfitUploadMapper mapper;
    public PriorProfitUploadForm(PriorProfitUploadMapper mapper) {this.mapper=mapper;}
    public Parsed parse(Path path) {
        List<Preview> rows=new ArrayList<>();List<DetailPreview> details=new ArrayList<>();List<Issue> issues=new ArrayList<>();
        try(InputStream in=Files.newInputStream(path);var book=new XSSFWorkbook(in)) {
            if(book.isMacroEnabled() || !book.getExternalLinksTable().isEmpty()) return failure("매크로·외부 연결은 허용하지 않습니다.");
            if(book.getNumberOfSheets()<1 || book.getNumberOfSheets()>2) return failure("월별 손익과 선택 판관비 상세 시트만 사용하세요.");
            for(Sheet sheet:book) for(Row row:sheet) for(Cell cell:row)
                if(cell.getCellType()==CellType.FORMULA) error(issues,row.getRowNum()+1,"파일","수식은 허용하지 않습니다.");
            Sheet sheet=book.getSheetAt(0);Row title=title(sheet,HEADERS);
            if(title==null) return failure("월별 손익의 필수 열이 없습니다.");
            var columns=columns(title,HEADERS);Set<String> months=new HashSet<>();
            for(int n=title.getRowNum()+1;n<=sheet.getLastRowNum();n++) {
                Row source=sheet.getRow(n);if(source==null || empty(source)) continue;
                if(rows.size()+details.size()>=10000) return failure("데이터는 10,000행 이하만 올릴 수 있습니다.");
                int line=n+1,start=issues.size();String month=value(source,columns,"연월");
                month(month,line,issues);
                if(!months.add(month)) error(issues,line,"연월","같은 연월이 중복됩니다.");
                long[] amounts=new long[11];
                for(int c=1;c<=11;c++) amounts[c-1]=money(value(source,columns,HEADERS.get(c)),c==8,line,HEADERS.get(c),issues);
                String ref=value(source,columns,"배분 기준 매출");
                Long allocated=ref.isBlank()?null:money(ref,false,line,"배분 기준 매출",issues);
                Snapshot profit=null;
                if(issues.size()==start) {
                    try {
                        long cost=Math.addExact(Math.addExact(amounts[1],amounts[2]),amounts[3]);
                        long sga=Math.addExact(Math.addExact(amounts[4],amounts[5]),amounts[6]);
                        long operating=Math.addExact(Math.subtractExact(Math.subtractExact(amounts[0],cost),sga),amounts[7]);
                        Math.subtractExact(Math.subtractExact(Math.addExact(operating,amounts[8]),amounts[9]),amounts[10]);
                        profit=new Snapshot(month,amounts[0],amounts[1],amounts[2],amounts[3],amounts[4],amounts[5],amounts[6],amounts[7],amounts[8],amounts[9],amounts[10],allocated);
                    } catch(ArithmeticException e) {error(issues,line,"금액","합산 금액이 원 단위 정수 범위를 넘습니다.");}
                }
                rows.add(new Preview(line,profit==null?"ERROR":"NORMAL",profit));
            }
            for(int m=1;m<=12;m++) if(!months.contains(String.format(Locale.ROOT,"2025-%02d",m)))
                error(issues,1,"연월","2025년 1~12월을 모두 입력하세요.");
            if(book.getNumberOfSheets()==2) {
                Sheet detail=book.getSheetAt(1);Row heading=title(detail,DETAIL_HEADERS);
                if(heading==null) return failure("판관비 상세의 연월·항목·금액 열이 없습니다.");
                var cols=columns(heading,DETAIL_HEADERS);Set<String> seen=new HashSet<>();
                List<UploadCode> codes=mapper.codes();List<UploadMapping> mappings=mapper.mappings();
                for(int n=heading.getRowNum()+1;n<=detail.getLastRowNum();n++) {
                    Row source=detail.getRow(n);if(source==null || empty(source)) continue;
                    if(rows.size()+details.size()>=10000) return failure("데이터는 10,000행 이하만 올릴 수 있습니다.");
                    int line=n+1,start=issues.size();String month=value(source,cols,"연월"),label=value(source,cols,"항목");
                    month(month,line,issues);
                    Set<String> matches=new HashSet<>();
                    for(UploadCode c:codes) if(c.group().equals("SGA_ITEM") && (c.code().equals(label)||c.name().equals(label))) matches.add(c.code());
                    for(UploadMapping mapping:mappings) if(mapping.group().equals("SGA_ITEM") && mapping.label().equals(label)
                            && codes.stream().anyMatch(c->c.group().equals("SGA_ITEM")&&c.code().equals(mapping.code()))) matches.add(mapping.code());
                    String code=matches.size()==1?matches.iterator().next():null;
                    if(code==null) issues.add(new Issue(line,"항목","ERROR","사용 중인 판관비 코드·표기 대응을 확인하세요.",label.substring(0,Math.min(500,label.length())),null,true));
                    if(code!=null && !seen.add(month+":"+code)) error(issues,line,"항목","연월·판관비 항목이 중복됩니다.");
                    long amount=money(value(source,cols,"금액"),false,line,"금액",issues);
                    Detail row=issues.size()==start?new Detail(month,code,amount):null;
                    details.add(new DetailPreview(line,row==null?"ERROR":"NORMAL",row));
                }
                for(Preview preview:rows) if(preview.profit()!=null) {
                    try {
                        long sum=0;for(DetailPreview p:details) if(p.detail()!=null&&p.detail().month().equals(preview.profit().month())) sum=Math.addExact(sum,p.detail().amount());
                        if(sum!=preview.profit().sgaInput()) error(issues,preview.row(),"판관비 입력 항목","판관비 상세 합계와 월별 판관비 입력 금액이 다릅니다.");
                    } catch(ArithmeticException e) {error(issues,preview.row(),"금액","판관비 상세 합계가 정수 범위를 넘습니다.");}
                }
            }
            if(rows.isEmpty()) return failure("검사할 데이터가 없습니다. 빈 파일로 전체 삭제할 수 없습니다.");
        } catch(IOException|org.apache.poi.openxml4j.exceptions.InvalidOperationException e) {return failure("올바른 xlsx 파일만 올릴 수 있습니다.");}
        catch(org.apache.poi.ooxml.POIXMLException|IllegalArgumentException e) {return failure("올바른 xlsx 파일만 올릴 수 있습니다.");}
        return new Parsed(List.copyOf(rows),List.copyOf(details),List.copyOf(issues));
    }
    private static boolean empty(Row row) {
        for(Cell cell:row) if(!text(cell).isBlank()) return false;return true;
    }
    private static Row title(Sheet sheet,List<String> headers) {
        for(int n=0;n<20;n++) {Row row=sheet.getRow(n);if(row==null) continue;
            Map<String,Integer> cols=columns(row,headers);
            if(cols.keySet().containsAll(headers.subList(0,headers==HEADERS?12:headers.size()))) return row;
        }return null;
    }
    private static Map<String,Integer> columns(Row row,List<String> headers) {
        Map<String,Integer> cols=new LinkedHashMap<>();
        for(Cell cell:row) {String key=text(cell).replace("*","").trim();
            if(headers.contains(key) && cols.put(key,cell.getColumnIndex())!=null) throw new IllegalArgumentException("중복 열 제목");
        }return cols;
    }
    private static String value(Row row,Map<String,Integer> columns,String key) {Integer c=columns.get(key);return c==null?"":text(row.getCell(c));}
    private static String text(Cell cell) {
        if(cell==null) return "";
        if(cell.getCellType()==CellType.NUMERIC) return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        return new DataFormatter(Locale.ROOT).formatCellValue(cell).trim();
    }
    private static long money(String raw,boolean signed,int row,String column,List<Issue> issues) {
        try {if(!raw.matches(signed?"-?[0-9]+":"[0-9]+")) throw new NumberFormatException();return Long.parseLong(raw);}
        catch(NumberFormatException e) {error(issues,row,column,signed?"원 단위 정수를 입력하세요.":"0 이상의 원 단위 정수를 입력하세요.");return 0;}
    }
    private static void month(String month,int row,List<Issue> issues) {
        if(!month.matches("2025-(0[1-9]|1[0-2])")) error(issues,row,"연월","2025년 1~12월만 이관할 수 있습니다.");
    }
    static void error(List<Issue> issues,int row,String column,String message) {issues.add(new Issue(row,column,"ERROR",message,null,null,false));}
    private static Parsed failure(String message) {return new Parsed(List.of(),List.of(),List.of(new Issue(1,"파일","ERROR",message,null,null,false)));}
}
