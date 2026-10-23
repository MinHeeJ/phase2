package com.sicost.uploadsops;

import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import static com.sicost.uploadsops.UploadModels.*;

final class PartnerUploadForm {
    static final String[] HEADERS={"구분","상호","사업자번호","대표자","업종","담당자","연락처","세금계산서 이메일","은행","계좌번호","예금주","비고"};
    private static final String[][] ALIASES={{"거래처 구분"},{"회사명","업체명"},{"사업자등록번호","사업자 번호"},{"대표자명"},{},{"담당자명"},{"전화","전화번호"},{"계산서 이메일"},{"은행명"},{"계좌"},{"예금주명"},{"메모"}};
    static ApiException fileError(String code,String message) { return new ApiException(400,code,message,"file"); }
    static String normalizeHeader(String value) { return value.replaceAll("[\\s*]",""); }
    static int headerIndex(String value) {
        String key=normalizeHeader(value);
        for(int i=0;i<HEADERS.length;i++) {
            if(normalizeHeader(HEADERS[i]).equals(key))return i;
            for(String alias:ALIASES[i])if(normalizeHeader(alias).equals(key))return i;
        }
        return -1;
    }
    static int findHeader(Sheet sheet) {
        for(int r=0;r<20;r++) {
            Row row=sheet.getRow(r);if(row==null)continue;
            for(Cell cell:row) if(headerIndex(new DataFormatter(Locale.ROOT).formatCellValue(cell))==2)return r;
        }
        throw fileError("HEADER_NOT_FOUND","사업자번호 제목이 있는 행을 찾을 수 없습니다.");
    }
    static Map<Integer,Integer> columns(Sheet sheet,int header) {
        Map<Integer,Integer> result=new LinkedHashMap<>();
        for(Cell cell:sheet.getRow(header)) {
            int index=headerIndex(new DataFormatter(Locale.ROOT).formatCellValue(cell));
            if(index>=0)result.putIfAbsent(index,cell.getColumnIndex());
        }
        for(int i=0;i<3;i++)if(!result.containsKey(i))throw fileError("REQUIRED_COLUMN_MISSING",HEADERS[i]+" 필수 열이 없습니다.");
        return result;
    }
    static String text(Cell cell, FormulaEvaluator evaluator) {
        if(cell==null)return "";
        CellType type=cell.getCellType();CellValue calculated=null;
        if(type==CellType.FORMULA) {
            try { calculated=evaluator.evaluate(cell);type=calculated==null?CellType.BLANK:calculated.getCellType(); }
            catch(RuntimeException ex) { throw fileError("UNSUPPORTED_FILE_TYPE","계산할 수 없는 수식입니다. 결과값으로 다시 올려 주세요."); }
        }
        if(type==CellType.NUMERIC) {
            double n=calculated==null?cell.getNumericCellValue():calculated.getNumberValue();
            if(DateUtil.isCellDateFormatted(cell))return DateUtil.getLocalDateTime(n).toLocalDate().toString();
            return BigDecimal.valueOf(n).stripTrailingZeros().toPlainString();
        }
        if(type==CellType.STRING)return (calculated==null?cell.getStringCellValue():calculated.getStringValue()).trim();
        if(type==CellType.BOOLEAN)return Boolean.toString(calculated==null?cell.getBooleanCellValue():calculated.getBooleanValue());
        return "";
    }
    static Parsed parse(XSSFWorkbook book,UploadMapper mapper) {
        if(book.getNumberOfSheets()==0)throw fileError("EMPTY_FILE","데이터가 없습니다.");
        if(!book.getExternalLinksTable().isEmpty())throw fileError("UNSUPPORTED_FILE_TYPE","외부 참조가 있는 파일은 올릴 수 없습니다.");
        Sheet sheet=book.getSheetAt(0);int header=findHeader(sheet);Map<Integer,Integer> columns=columns(sheet,header);
        FormulaEvaluator evaluator=book.getCreationHelper().createFormulaEvaluator();
        List<Code> codes=mapper.codes(), mappings=mapper.mappings();
        List<PartnerRow> rows=new ArrayList<>();List<Issue> issues=new ArrayList<>();Map<String,Integer> keys=new HashMap<>();
        for(int n=header+1;n<=sheet.getLastRowNum();n++) {
            Row row=sheet.getRow(n);if(row==null)continue;
            String[] v=new String[12];Arrays.fill(v,"");
            for(var column:columns.entrySet())v[column.getKey()]=text(row.getCell(column.getValue()),evaluator);
            if(Arrays.stream(v).allMatch(String::isEmpty)||v[1].startsWith("예시)"))continue;
            if(rows.size()>=10000)throw fileError("TOO_MANY_ROWS","데이터는 10,000행 이하로 올려 주세요.");
            int r=n+1;
            String kind=resolve(v[0],"PARTNER_KIND",0,r,issues,codes,mappings);
            if(v[1].isEmpty())add(issues,r,1,v[1],"상호가 비어 있습니다.");
            limit(v,1,50,r,issues);limit(v,3,20,r,issues);limit(v,5,20,r,issues);limit(v,6,20,r,issues);limit(v,11,500,r,issues);
            String business=v[2];if(business.matches("[0-9]{10}"))business=business.substring(0,3)+"-"+business.substring(3,5)+"-"+business.substring(5);
            if(!business.matches("[0-9]{3}-[0-9]{2}-[0-9]{5}"))add(issues,r,2,v[2],"사업자번호 10자리를 입력해 주세요. (예: 123-45-67890)");
            else {
                Integer first=keys.putIfAbsent(business,r);
                if(first!=null)add(issues,r,2,v[2],"파일 안에서 "+first+"행과 사업자번호가 같습니다.");
                String existing=mapper.existingPartner(business);
                if(existing!=null)add(issues,r,2,v[2],"이미 등록된 사업자번호입니다. ("+existing+")");
            }
            String industry=v[4].isEmpty()?null:resolve(v[4],"INDUSTRY",4,r,issues,codes,mappings);
            String bank=null,email=null,account=null,depositor=null;
            if("CUST".equals(kind)||"BOTH".equals(kind)) {
                email=empty(v[7]);
                if(email==null)add(issues,r,7,v[7],"고객사는 세금계산서 이메일이 꼭 필요합니다.");
                else if(email.length()>100||!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))add(issues,r,7,v[7],"이메일 형식이 맞지 않습니다. (예: tax@company.co.kr)");
            } else if("VEND".equals(kind)&&!v[7].isEmpty())warn(issues,r,7,v[7],"협력사는 세금계산서 이메일을 쓰지 않아 무시했습니다.");
            if("VEND".equals(kind)||"BOTH".equals(kind)) {
                for(int c=8;c<=10;c++)if(v[c].isEmpty())add(issues,r,c,v[c],"협력사는 지급 계좌(은행·계좌번호·예금주)가 꼭 필요합니다.");
                if(!v[8].isEmpty())bank=resolve(v[8],"BANK",8,r,issues,codes,mappings);
                account=empty(v[9]);depositor=empty(v[10]);
                if(account!=null&&!account.matches("[0-9-]{8,20}"))add(issues,r,9,v[9],"계좌번호를 숫자와 하이픈(-)으로 입력해 주세요.");
                limit(v,10,30,r,issues);
            } else if("CUST".equals(kind)&&(!v[8].isEmpty()||!v[9].isEmpty()||!v[10].isEmpty()))warn(issues,r,8,v[8],"고객사는 지급 계좌를 쓰지 않아 은행·계좌번호·예금주를 무시했습니다.");
            rows.add(new PartnerRow(r,kind,v[1],business,empty(v[3]),industry,empty(v[5]),empty(v[6]),email,bank,account,depositor,empty(v[11])));
        }
        if(rows.isEmpty())throw fileError("EMPTY_FILE","데이터가 없습니다.");
        issues.sort(Comparator.comparingInt(Issue::rowNo));
        return new Parsed(List.copyOf(rows),List.copyOf(issues));
    }
    static String empty(String value) { return value.isEmpty()?null:value; }
    private static void limit(String[] v,int c,int max,int row,List<Issue> issues) {
        if(v[c].length()>max)add(issues,row,c,v[c],HEADERS[c]+"는 "+max+"자까지 쓸 수 있습니다.");
    }
    private static String resolve(String raw,String group,int column,int row,List<Issue> issues,List<Code> codes,List<Code> mappings) {
        List<Code> active=codes.stream().filter(c->c.group().equals(group)).toList();
        for(Code code:active)if(code.name().equals(raw))return code.code();
        for(Code code:active)if(code.code().equals(raw))return code.code();
        Map<String,Code> candidates=new LinkedHashMap<>();
        for(Code map:mappings)if(map.group().equals(group)&&raw.equals(map.label())&&active.stream().anyMatch(c->c.code().equals(map.code())))candidates.put(map.code(),map);
        if(candidates.size()==1) {
            Code code=candidates.values().iterator().next();warn(issues,row,column,raw,"'"+raw+"'를 '"+code.name()+"'(으)로 바꿔 읽었습니다.");return code.code();
        }
        String message=column==0?"구분은 고객사·협력사·둘 다 중 하나로 적어 주세요.":"'"+raw+"'은(는) 등록된 "+HEADERS[column]+"이 아닙니다. 목록에 있는 이름으로 적거나 [대응 등록]으로 연결해 주세요.";
        issues.add(new Issue("ERROR",row,HEADERS[column],cut(raw),cut(message),new MappingFix("MAPPING",group)));return null;
    }
    private static String cut(String value) { return value.length()>500?value.substring(0,500):value; }
    private static void add(List<Issue> issues,int row,int column,String raw,String message) { issues.add(new Issue("ERROR",row,HEADERS[column],cut(raw),message,null)); }
    private static void warn(List<Issue> issues,int row,int column,String raw,String message) { issues.add(new Issue("WARN",row,HEADERS[column],cut(raw),message,null)); }
}
