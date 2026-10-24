package com.sicost.uploadsops;

import com.sicost.common.api.ApiException;
import com.sicost.partners.PartnerModels.PartnerSave;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;
import static com.sicost.uploadsops.UploadModels.*;

@Component
public class PartnerUploadForm {
    public static final List<String> HEADERS=List.of("구분","상호","사업자번호","대표자","업종","담당자","연락처","세금계산서 이메일","은행","계좌번호","예금주","비고");
    private static final List<List<String>> ALIASES=List.of(List.of("거래처 구분"),List.of("회사명","업체명"),List.of("사업자등록번호","사업자 번호"),List.of("대표자명"),List.of(),List.of("담당자명"),List.of("전화","전화번호"),List.of("계산서 이메일"),List.of("은행명"),List.of("계좌"),List.of("예금주명"),List.of("메모"));
    private final UploadMapper mapper;
    public PartnerUploadForm(UploadMapper mapper) {this.mapper=mapper;}
    static ApiException fileError(String code,String message) {return new ApiException(400,code,message,"file");}
    static String normalize(String text) {return text.replaceAll("[\\s*]","");}
    static int headerIndex(String text) {
        String normalized=normalize(text);
        for(int i=0;i<HEADERS.size();i++) if(normalize(HEADERS.get(i)).equals(normalized)||ALIASES.get(i).stream().anyMatch(a->normalize(a).equals(normalized))) return i;
        return -1;
    }
    static String value(Cell cell,FormulaEvaluator evaluator) {
        if(cell==null)return "";
        CellValue result=cell.getCellType()==CellType.FORMULA?evaluator.evaluate(cell):null;
        CellType type=result==null?cell.getCellType():result.getCellType();
        return switch(type) {
            case NUMERIC -> BigDecimal.valueOf(result==null?cell.getNumericCellValue():result.getNumberValue()).stripTrailingZeros().toPlainString();
            case STRING -> (result==null?cell.getStringCellValue():result.getStringValue()).trim();
            case BOOLEAN -> Boolean.toString(result==null?cell.getBooleanCellValue():result.getBooleanValue());
            case ERROR -> "#ERROR";
            default -> "";
        };
    }
    static Row header(Sheet sheet,FormulaEvaluator evaluator) {
        for(int i=0;i<20;i++) {Row row=sheet.getRow(i);if(row!=null)for(Cell cell:row)if(headerIndex(value(cell,evaluator))==2)return row;}
        throw fileError("HEADER_NOT_FOUND","제목 줄을 찾을 수 없습니다. 양식의 제목을 확인해 주세요.");
    }
    static Map<Integer,Integer> columns(Row header,FormulaEvaluator evaluator) {
        Map<Integer,Integer> columns=new HashMap<>();
        for(Cell cell:header) {int i=headerIndex(value(cell,evaluator));if(i>=0&&columns.put(i,cell.getColumnIndex())!=null)throw fileError("REQUIRED_COLUMN_MISSING","중복된 제목이 있습니다. 열 제목을 확인해 주세요.");}
        if(!columns.keySet().containsAll(Set.of(0,1,2)))throw fileError("REQUIRED_COLUMN_MISSING","필수 열(구분·상호·사업자번호)이 없습니다.");
        return columns;
    }
    public ParsedUpload parse(InputStream input)throws IOException {
        try(XSSFWorkbook workbook=new XSSFWorkbook(input)) {
            if(workbook.isMacroEnabled()||!workbook.getExternalLinksTable().isEmpty())throw fileError("UNSUPPORTED_FILE_TYPE","외부 연결 또는 매크로가 있는 파일은 올릴 수 없습니다.");
            if(workbook.getNumberOfSheets()==0)throw fileError("HEADER_NOT_FOUND","첫 시트가 없습니다.");
            Sheet sheet=workbook.getSheetAt(0);FormulaEvaluator evaluator=workbook.getCreationHelper().createFormulaEvaluator();
            DataFormatter identifiers=new DataFormatter(Locale.ROOT);
            Row title=header(sheet,evaluator);Map<Integer,Integer> columns=columns(title,evaluator);
            List<UploadCode> codes=mapper.codes();List<UploadMapping> mappings=mapper.mappings();
            List<PartnerSave> rows=new ArrayList<>();List<UploadIssue> issues=new ArrayList<>();Map<String,Integer> seen=new HashMap<>();
            for(int n=title.getRowNum()+1;n<=sheet.getLastRowNum();n++) {
                Row row=sheet.getRow(n);if(row==null)continue;
                String[] v=new String[12];Arrays.fill(v,"");for(var c:columns.entrySet()) {
                    Cell cell=row.getCell(c.getValue());
                    v[c.getKey()]=(c.getKey()==6||c.getKey()==9)?identifiers.formatCellValue(cell,evaluator).trim():value(cell,evaluator);
                }
                if(Arrays.stream(v).allMatch(String::isBlank)||v[1].startsWith("예시)"))continue;
                if(rows.size()>=10000)throw fileError("TOO_MANY_ROWS","데이터는 10,000행 이하만 올릴 수 있습니다.");
                int line=n+1;
                String kind=resolve(new CodeInput(0,"PARTNER_KIND",line,v[0]),codes,mappings,issues);
                if(v[1].isEmpty())issue(issues,line,1,v[1],"상호가 비어 있습니다.");
                length(issues,line,1,v[1],50);length(issues,line,3,v[3],20);length(issues,line,5,v[5],20);length(issues,line,6,v[6],20);length(issues,line,11,v[11],500);
                String biz=v[2];if(biz.matches("[0-9]{10}"))biz=biz.substring(0,3)+"-"+biz.substring(3,5)+"-"+biz.substring(5);
                if(!biz.matches("[0-9]{3}-[0-9]{2}-[0-9]{5}"))issue(issues,line,2,v[2],"사업자번호 10자리를 입력해 주세요. (예: 123-45-67890)");
                else {
                    Integer first=seen.putIfAbsent(biz,line);if(first!=null)issue(issues,line,2,v[2],"파일 안에서 "+first+"행과 사업자번호가 같습니다.");
                    String existing=mapper.duplicateName(biz);if(existing!=null)issue(issues,line,2,v[2],"이미 등록된 사업자번호입니다. ("+existing+")");
                }
                String industry=v[4].isEmpty()?null:resolve(new CodeInput(4,"INDUSTRY",line,v[4]),codes,mappings,issues);
                String email=null,bank=null,account=null,depositor=null;
                if("CUST".equals(kind)||"BOTH".equals(kind)) {
                    email=empty(v[7]);
                    if(email==null)issue(issues,line,7,v[7],"고객사는 세금계산서 이메일이 꼭 필요합니다.");
                    else {length(issues,line,7,email,100);if(!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))issue(issues,line,7,email,"이메일 형식이 맞지 않습니다. (예: tax@company.co.kr)");}
                }
                if("VEND".equals(kind)||"BOTH".equals(kind)) {
                    for(int i:new int[]{8,9,10})if(v[i].isEmpty())issue(issues,line,i,v[i],"협력사는 지급 계좌(은행·계좌번호·예금주)가 꼭 필요합니다.");
                    bank=v[8].isEmpty()?null:resolve(new CodeInput(8,"BANK",line,v[8]),codes,mappings,issues);account=empty(v[9]);depositor=empty(v[10]);
                    if(account!=null&&!account.matches("[0-9-]{8,20}"))issue(issues,line,9,account,"계좌번호를 숫자와 하이픈(-)으로 입력해 주세요.");
                    length(issues,line,10,v[10],30);
                }
                if("CUST".equals(kind)&&(!v[8].isEmpty()||!v[9].isEmpty()||!v[10].isEmpty()))issues.add(new UploadIssue("WARN",line,HEADERS.get(8),v[8],"고객사는 지급 계좌를 쓰지 않아 은행·계좌번호·예금주를 무시했습니다.",null));
                if("VEND".equals(kind)&&!v[7].isEmpty())issues.add(new UploadIssue("WARN",line,HEADERS.get(7),v[7],"협력사는 세금계산서 이메일을 쓰지 않아 무시했습니다.",null));
                rows.add(new PartnerSave(kind,v[1],biz,empty(v[3]),industry,empty(v[5]),empty(v[6]),empty(v[11]),email,bank,account,depositor));
            }
            if(rows.isEmpty())throw fileError("EMPTY_FILE","검사할 데이터가 없습니다.");
            return new ParsedUpload(List.copyOf(rows),List.copyOf(issues));
        } catch(ApiException ex){throw ex;}
        catch(DataAccessException ex){throw ex;}
        catch(RuntimeException ex){throw fileError("UNSUPPORTED_FILE_TYPE","올바른 xlsx 파일만 올릴 수 있습니다.");}
    }
    private record CodeInput(int column,String group,int row,String raw) {}
    private String resolve(CodeInput in,List<UploadCode> codes,List<UploadMapping> mappings,List<UploadIssue> issues) {
        List<UploadCode> group=codes.stream().filter(c->c.group().equals(in.group())).toList();
        for(UploadCode c:group)if(c.name().equals(in.raw()))return c.code();
        for(UploadCode c:group)if(c.code().equals(in.raw()))return c.code();
        Set<String> targets=new HashSet<>();mappings.stream().filter(m->m.group().equals(in.group())&&m.label().equals(in.raw())).forEach(m->targets.add(m.code()));
        if(targets.size()==1)for(UploadCode c:group)if(targets.contains(c.code())) {
            issues.add(new UploadIssue("WARN",in.row(),HEADERS.get(in.column()),in.raw(),"'"+in.raw()+"'를 '"+c.name()+"'(으)로 바꿔 읽었습니다.",null));return c.code();
        }
        String message=in.column()==0?"구분은 고객사·협력사·둘 다 중 하나로 적어 주세요.":"'"+display(in.raw())+"'은(는) 등록된 "+HEADERS.get(in.column())+"이 아닙니다. 목록에 있는 이름으로 적거나 [대응 등록]으로 연결해 주세요.";
        issues.add(new UploadIssue("ERROR",in.row(),HEADERS.get(in.column()),in.raw(),message,new MappingAction("MAPPING",in.group())));return null;
    }
    private static String display(String raw){return raw.length()>200?raw.substring(0,200)+"…":raw;}
    private static String empty(String v){return v.isEmpty()?null:v;}
    private static void issue(List<UploadIssue> issues,int row,int column,String raw,String message) {issues.add(new UploadIssue("ERROR",row,HEADERS.get(column),raw,message,null));}
    private static void length(List<UploadIssue> issues,int row,int column,String value,int max) {if(value.length()>max)issue(issues,row,column,value,HEADERS.get(column)+"는 "+max+"자까지 쓸 수 있습니다.");}
}
