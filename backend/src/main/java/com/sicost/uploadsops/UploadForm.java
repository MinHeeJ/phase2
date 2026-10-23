package com.sicost.uploadsops;

import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import static com.sicost.uploadsops.UploadModels.*;

public final class UploadForm {
    static final String[] TITLES={"구분","상호","사업자번호","대표자","업종","담당자","연락처","세금계산서 이메일","은행","계좌번호","예금주","비고"};
    private static final String[][] ALIASES={{"거래처 구분"},{"회사명","업체명"},{"사업자등록번호","사업자 번호"},{"대표자명"},{},{"담당자명"},{"전화","전화번호"},{"계산서 이메일"},{"은행명"},{"계좌"},{"예금주명"},{"메모"}};
    private UploadForm() {}
    static String compact(String s) { return s.replaceAll("[\\s*]",""); }
    static int column(String s) {
        for(int i=0;i<TITLES.length;i++) {
            if(compact(TITLES[i]).equals(compact(s)))return i;
            for(String a:ALIASES[i])if(compact(a).equals(compact(s)))return i;
        }
        return -1;
    }
    static String value(Cell cell) {
        if(cell==null)return "";
        CellType type=cell.getCellType()==CellType.FORMULA?cell.getCachedFormulaResultType():cell.getCellType();
        return switch(type) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            default -> "";
        };
    }
    static int header(Sheet sheet) {
        for(int r=0;r<20;r++) {
            Row row=sheet.getRow(r);if(row==null)continue;
            for(Cell c:row)if(column(value(c))==2)return r;
        }
        throw fileError("HEADER_NOT_FOUND","제목 줄을 찾을 수 없습니다.");
    }
    static Map<Integer,Integer> columns(Sheet sheet,int header) {
        Map<Integer,Integer> cols=new HashMap<>();
        for(Cell c:sheet.getRow(header)) {
            int key=column(value(c));if(key<0)continue;
            if(cols.putIfAbsent(key,c.getColumnIndex())!=null && key<3)throw fileError("REQUIRED_COLUMN_MISSING","필수 열이 중복되었습니다.");
        }
        for(int c=0;c<3;c++)if(!cols.containsKey(c))throw fileError("REQUIRED_COLUMN_MISSING","필수 열이 없습니다: "+TITLES[c]);
        return cols;
    }
    static Parsed parse(Workbook book,UploadMapper mapper) {
        if(book.getNumberOfSheets()==0)throw fileError("EMPTY_FILE","데이터가 없습니다.");
        Sheet sheet=book.getSheetAt(0);int h=header(sheet);Map<Integer,Integer> cols=columns(sheet,h);
        List<Code> codes=mapper.codes(),maps=mapper.mappings();
        List<Partner> rows=new ArrayList<>();List<Issue> issues=new ArrayList<>();Map<String,Integer> seen=new HashMap<>();
        for(int r=h+1;r<=sheet.getLastRowNum();r++) {
            Row row=sheet.getRow(r);String[] v=new String[12];
            for(int c=0;c<12;c++)v[c]=row==null||!cols.containsKey(c)?"":value(row.getCell(cols.get(c)));
            if(Arrays.stream(v).allMatch(String::isBlank)||v[1].startsWith("예시)"))continue;
            if(rows.size()==10000)throw fileError("TOO_MANY_ROWS","데이터는 10,000행까지 올릴 수 있습니다.");
            int n=r+1;
            String kind=resolve(v[0],"PARTNER_KIND",0,n,codes,maps,issues);
            if(v[1].isEmpty())error(issues,n,1,v[1],"상호가 비어 있습니다.");
            length(issues,n,1,v[1],50);length(issues,n,3,v[3],20);length(issues,n,5,v[5],20);length(issues,n,6,v[6],20);length(issues,n,11,v[11],500);
            String biz=v[2];if(biz.matches("[0-9]{10}"))biz=biz.substring(0,3)+"-"+biz.substring(3,5)+"-"+biz.substring(5);
            if(!biz.matches("[0-9]{3}-[0-9]{2}-[0-9]{5}"))error(issues,n,2,v[2],"사업자번호 10자리를 입력해 주세요. (예: 123-45-67890)");
            else {
                Integer first=seen.putIfAbsent(biz,n);
                if(first!=null)error(issues,n,2,v[2],"파일 안에서 "+first+"행과 사업자번호가 같습니다.");
                String existing=mapper.existing(biz);if(existing!=null)error(issues,n,2,v[2],"이미 등록된 사업자번호입니다. ("+existing+")");
            }
            String industry=v[4].isEmpty()?null:resolve(v[4],"INDUSTRY",4,n,codes,maps,issues);
            String email=null,bank=null,account=null,holder=null;
            if("CUST".equals(kind)||"BOTH".equals(kind)) {
                email=nullable(v[7]);
                if(email==null)error(issues,n,7,v[7],"고객사는 세금계산서 이메일이 꼭 필요합니다.");
                else if(email.length()>100||!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))error(issues,n,7,email,"이메일 형식이 맞지 않습니다. (예: tax@company.co.kr)");
            } else if("VEND".equals(kind)&&!v[7].isEmpty())warn(issues,n,7,v[7],"협력사는 세금계산서 이메일을 쓰지 않아 무시했습니다.");
            if("VEND".equals(kind)||"BOTH".equals(kind)) {
                for(int c=8;c<=10;c++)if(v[c].isEmpty())error(issues,n,c,v[c],"협력사는 지급 계좌(은행·계좌번호·예금주)가 꼭 필요합니다.");
                bank=v[8].isEmpty()?null:resolve(v[8],"BANK",8,n,codes,maps,issues);account=nullable(v[9]);holder=nullable(v[10]);
                if(account!=null&&!account.matches("[0-9-]{8,20}"))error(issues,n,9,account,"계좌번호를 숫자와 하이픈(-)으로 입력해 주세요.");
                length(issues,n,10,v[10],30);
            } else if("CUST".equals(kind)&&(!v[8].isEmpty()||!v[9].isEmpty()||!v[10].isEmpty()))warn(issues,n,8,v[8],"고객사는 지급 계좌를 쓰지 않아 은행·계좌번호·예금주를 무시했습니다.");
            rows.add(new Partner(n,kind,v[1],biz,nullable(v[3]),industry,nullable(v[5]),nullable(v[6]),email,bank,account,holder,nullable(v[11])));
        }
        if(rows.isEmpty())throw fileError("EMPTY_FILE","데이터가 없습니다.");
        issues.sort(Comparator.comparingInt(Issue::rowNo));return new Parsed(h,List.copyOf(rows),List.copyOf(issues));
    }
    static String resolve(String raw,String group,int column,int row,List<Code> codes,List<Code> maps,List<Issue> issues) {
        for(Code c:codes)if(c.group().equals(group)&&c.name().equals(raw))return c.code();
        for(Code c:codes)if(c.group().equals(group)&&c.code().equals(raw))return c.code();
        List<Code> matches=maps.stream().filter(c->c.group().equals(group)&&Objects.equals(c.label(),raw)).toList();
        if(matches.stream().map(Code::code).distinct().count()==1) {
            Code c=matches.get(0);warn(issues,row,column,raw,"'"+raw+"'를 '"+c.name()+"'(으)로 바꿔 읽었습니다.");return c.code();
        }
        String message=group.equals("PARTNER_KIND")?"구분은 고객사·협력사·둘 다 중 하나로 적어 주세요.":"'"+raw+"'은(는) 등록된 "+(group.equals("BANK")?"은행":"업종")+"이 아닙니다. 목록에 있는 이름으로 적거나 [대응 등록]으로 연결해 주세요.";
        issues.add(new Issue("ERROR",row,TITLES[column],clip(raw),message,new Fix("MAPPING",group)));return null;
    }
    static String nullable(String s){return s.isEmpty()?null:s;}
    static String clip(String s){return s.length()>500?s.substring(0,500):s;}
    static void length(List<Issue> list,int r,int c,String v,int max){if(v.length()>max)error(list,r,c,v,TITLES[c]+"는 "+max+"자까지 쓸 수 있습니다.");}
    static void error(List<Issue> list,int r,int c,String v,String msg){list.add(new Issue("ERROR",r,TITLES[c],clip(v),msg,null));}
    static void warn(List<Issue> list,int r,int c,String v,String msg){list.add(new Issue("WARN",r,TITLES[c],clip(v),msg,null));}
    static ApiException fileError(String code,String message){return new ApiException(400,code,message,"file");}
}
