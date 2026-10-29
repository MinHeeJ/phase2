package com.sicost.uploadsops;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.DataScope;
import com.sicost.common.validation.CalendarValues;
import com.sicost.common.validation.DeveloperManagementNumbers;
import com.sicost.shared.SharedReadService;
import java.io.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import static com.sicost.uploadsops.PersonnelUploadModels.*;
import static com.sicost.uploadsops.UploadModels.*;

@Component
public class PersonnelUploadForm {
    public static final List<String> ATTENDANCE_HEADERS=List.of("연월","프로젝트ID","관리번호","이름","근무일수","정규 근무시간","초과 근무시간","해당 월 근무일수");
    public static final List<String> DEVELOPER_HEADERS=List.of("관리번호","이름","연락처","직원구분","소속","직위","업무 구분","등급","주 직무","보조 직무","스킬","영업 파트","입사일","월 단가","상태","시작일","종료일","비고");
    public static final List<String> SALARY_HEADERS=List.of("","이름","소속","직위","구분","프로젝트명","기간","계약유형","단가","월 급여","수당","4대보험(회사)","4대보험(본인)","3.3% 원천세","퇴직금 적립","실지급액","개인별 원가","팀비","프로젝트경비","원가합계","영업이익","가동","가득","관리번호","부가세","근로소득세");
    private static final Map<String,String> ALIASES=Map.ofEntries(Map.entry("사번","관리번호"),Map.entry("성명","이름"),Map.entry("전화번호","연락처"),Map.entry("소속사","소속"),Map.entry("업체명","소속"),Map.entry("직무","주 직무"),Map.entry("보유스킬","스킬"),Map.entry("파트","영업 파트"),Map.entry("계약일","입사일"),Map.entry("단가","월 단가"),Map.entry("메모","비고"),Map.entry("직접/간접","업무 구분"),Map.entry("근무상태","상태"));
    private final PersonnelUploadMapper mapper;
    private final SharedReadService shared;
    public PersonnelUploadForm(PersonnelUploadMapper mapper,SharedReadService shared){this.mapper=mapper;this.shared=shared;}
    public static List<String> headers(String form){return "B.3".equals(form)?ATTENDANCE_HEADERS:"B.8".equals(form)?DEVELOPER_HEADERS:SALARY_HEADERS;}
    static String label(String raw,String form){String key=PartnerUploadForm.normalize(raw);if("B.8".equals(form)&&key.equals("구분"))return "직원구분";for(String h:headers(form))if(!h.isBlank()&&PartnerUploadForm.normalize(h).equals(key))return h;return "B.8".equals(form)?ALIASES.getOrDefault(key,raw):raw;}
    static Row title(Sheet sheet,String form){String marker="B.8".equals(form)?"이름":"관리번호";for(int i=0;i<20;i++){Row r=sheet.getRow(i);if(r!=null)for(Cell c:r)if(c.getCellType()==CellType.STRING&&label(c.getStringCellValue(),form).equals(marker))return r;}throw PartnerUploadForm.fileError("HEADER_NOT_FOUND","제목 줄을 찾을 수 없습니다. 양식의 제목을 확인해 주세요.");}
    public Parsed parse(InputStream input,String form,String month)throws IOException {
        try(var book=new XSSFWorkbook(input)) {
            if(book.isMacroEnabled()||!book.getExternalLinksTable().isEmpty())throw PartnerUploadForm.fileError("UNSUPPORTED_FILE_TYPE","외부 연결 또는 매크로가 있는 파일은 올릴 수 없습니다.");
            if(book.getNumberOfSheets()==0)throw PartnerUploadForm.fileError("HEADER_NOT_FOUND","첫 시트가 없습니다.");
            Sheet sheet=book.getSheetAt(0);Row title=title(sheet,form);Map<String,Integer> columns=new LinkedHashMap<>();
            for(Cell c:title){if(c.getCellType()!=CellType.STRING)continue;String h=label(c.getStringCellValue(),form);if(form.equals("B.1")&&Set.of("직위","프로젝트명","기간","계약유형","단가","원가합계","영업이익","가동","가득").contains(h))continue;if(headers(form).contains(h)&&!h.isBlank()&&columns.put(h,c.getColumnIndex())!=null)throw PartnerUploadForm.fileError("REQUIRED_COLUMN_MISSING","중복된 제목이 있습니다.");}
            Set<String> required="B.8".equals(form)?Set.of("이름","직원구분","주 직무"):Set.of("관리번호","이름","월 급여");
            if(!columns.keySet().containsAll(required))throw PartnerUploadForm.fileError("REQUIRED_COLUMN_MISSING","필수 열이 없습니다.");
            var codes=mapper.codes();var mappings=mapper.mappings();List<Preview> rows=new ArrayList<>();List<Issue> issues=new ArrayList<>();Map<String,Integer> seen=new HashMap<>();
            for(int n=title.getRowNum()+1;n<=sheet.getLastRowNum();n++) {
                Row row=sheet.getRow(n);if(row==null)continue;Map<String,String> values=new LinkedHashMap<>();boolean formula=false;
                for(var e:columns.entrySet()){Cell c=row.getCell(e.getValue());boolean formulaCell=c!=null&&c.getCellType()==CellType.FORMULA;if(formulaCell)formula=true;String value=c==null||formulaCell?"":c.getCellType()==CellType.NUMERIC&&DateUtil.isCellDateFormatted(c)?c.getLocalDateTimeCellValue().toLocalDate().toString():PartnerUploadForm.value(c,book.getCreationHelper().createFormulaEvaluator());values.put(e.getKey(),value);}
                if(values.values().stream().allMatch(String::isBlank)||values.getOrDefault("이름","").startsWith("예시)"))continue;
                if(rows.size()>=10000)throw PartnerUploadForm.fileError("TOO_MANY_ROWS","데이터는 10,000행 이하만 올릴 수 있습니다.");
                int line=n+1;int before=issues.size();Map<String,Object> normalized=null;
                if(formula)error(issues,line,"파일","","수식이 있는 데이터는 올릴 수 없습니다.");
                else {String number=values.getOrDefault("관리번호","");if(!number.isBlank()){Integer first=seen.putIfAbsent(number,line);if(first!=null)error(issues,line,"관리번호",number,"파일 안에서 "+first+"행과 관리번호가 같습니다.");}
                    normalized="B.8".equals(form)?developer(values,line,codes,mappings,issues):salary(values,line,month,codes,mappings,issues);}
                String severity=issues.subList(before,issues.size()).stream().anyMatch(i->i.severity().equals("ERROR"))?"ERROR":issues.size()>before?"WARNING":"NORMAL";
                if("ERROR".equals(severity))normalized=null;
                rows.add(new Preview(line,severity,"B.8".equals(form)?normalized:null,"B.1".equals(form)?normalized:null));
            }
            if(rows.isEmpty())throw PartnerUploadForm.fileError("EMPTY_FILE","검사할 데이터가 없습니다.");
            return new Parsed(List.copyOf(rows),List.copyOf(issues));
        }catch(ApiException ex){throw ex;}catch(RuntimeException ex){throw PartnerUploadForm.fileError("UNSUPPORTED_FILE_TYPE","올바른 xlsx 파일만 올릴 수 있습니다.");}
    }
    private Map<String,Object> developer(Map<String,String> v,int row,List<UploadCode> codes,List<UploadMapping> mappings,List<Issue> issues){
        Map<String,Object> d=new LinkedHashMap<>();String number=v.getOrDefault("관리번호","");
        try{d.put("mng_no",DeveloperManagementNumbers.supplied(number));}catch(ApiException e){error(issues,row,"관리번호",number,"관리번호는 영문 대문자·숫자·하이픈으로 20자까지 씁니다.");}
        if(!number.isBlank()&&mapper.developer(number)!=null)error(issues,row,"관리번호",number,"이미 있는 관리번호입니다.");
        text(d,v,"이름","dev_nm",50,true,row,issues);text(d,v,"연락처","tel_no",30,false,row,issues);text(d,v,"비고","rmrk_desc",500,false,row,issues);
        String emp=resolve("직원구분","EMP_TYPE",v.getOrDefault("직원구분",""),row,codes,mappings,issues,true);d.put("emp_se_cd",emp);
        String affiliation=v.getOrDefault("소속","");d.put("vndr_ptnr_id",null);
        if("OS".equals(emp)){
            if(affiliation.isBlank()||affiliation.equals("자사"))error(issues,row,"소속",affiliation,"외주는 소속 협력사가 꼭 필요합니다.");
            else {var partners=mapper.partners(affiliation);if(partners.size()==1)d.put("vndr_ptnr_id",partners.get(0).get("ptnr_id"));else error(issues,row,"소속",affiliation,partners.isEmpty()?"'"+affiliation+"'은(는) 등록된 협력사가 아닙니다. 거래처에 먼저 등록해 주세요.":"같은 상호의 협력사가 여럿입니다. 사업자번호로 적어 주세요.");}
        }else if(!affiliation.isBlank()&&!affiliation.equals("자사"))warning(issues,row,"소속",affiliation,null,"외주가 아니어서 소속을 자사로 저장합니다.");
        String[][] fields={{"직위","POSITION","pstn_cd"},{"업무 구분","WORK_TYPE","duty_se_cd"},{"등급","GRADE","grd_cd"},{"주 직무","JOB","main_job_cd"}};
        for(var f:fields){String raw=v.getOrDefault(f[0],"");d.put(f[2],raw.isBlank()&&f[2].equals("duty_se_cd")?"DIRECT":resolve(f[0],f[1],raw,row,codes,mappings,issues,f[2].equals("main_job_cd")));}
        d.put("sub_job",multiple(v.getOrDefault("보조 직무",""),"보조 직무","JOB","[,/]",row,codes,mappings,issues).stream().filter(c->!c.equals(d.get("main_job_cd"))).toList());
        d.put("skill",multiple(v.getOrDefault("스킬",""),"스킬","SKILL",",",row,codes,mappings,issues));
        d.put("bsn_part_id",null);String part=v.getOrDefault("영업 파트","");if(!part.isBlank()){var parts=mapper.parts(part);if(parts.size()!=1)error(issues,row,"영업 파트",part,"'"+part+"'은(는) 등록된 영업 파트가 아닙니다. 사용 중 파트명을 확인해 주세요.");else d.put("bsn_part_id",parts.get(0).get("bsn_part_id"));}
        d.put("hire_dt",date(v.getOrDefault("입사일",""),"입사일",row,issues));d.put("dflt_untprc_amt",money(v.getOrDefault("월 단가",""),"월 단가",row,issues,false));
        d.put("rsgn_dt",null);d.put("work_status",null);String status=v.getOrDefault("상태","");
        if(!Set.of("","재직","대기중").contains(status)){
            String code=resolve("상태","WORK_STATUS",status,row,codes,mappings,issues,true);LocalDate begin=date(v.getOrDefault("시작일",""),"시작일",row,issues),end=date(v.getOrDefault("종료일",""),"종료일",row,issues);
            if(begin==null)error(issues,row,"시작일","","상태가 있으면 시작일이 꼭 필요합니다.");if(begin!=null&&end!=null&&end.isBefore(begin))error(issues,row,"시작일",end.toString(),"종료일은 시작일 이후여야 합니다.");
            Map<String,Object> work=new LinkedHashMap<>();work.put("work_stts_cd",code);work.put("bgn_dt",begin);work.put("end_dt",end);work.put("paid_yn","N");work.put("rmrk_desc",null);d.put("work_status",work);if("RESIGN".equals(code))d.put("rsgn_dt",begin);
        }return d;
    }
    private Map<String,Object> salary(Map<String,String> v,int row,String month,List<UploadCode> codes,List<UploadMapping> mappings,List<Issue> issues){
        Map<String,Object> s=new LinkedHashMap<>();String number=v.getOrDefault("관리번호","");String name=v.getOrDefault("이름","");s.put("mng_no",number);s.put("dev_nm",name);s.put("affiliation",v.getOrDefault("소속",""));
        if(number.isBlank())error(issues,row,"관리번호",number,"관리번호가 비어 있습니다.");if(name.isBlank())error(issues,row,"이름",name,"이름이 비어 있습니다.");
        var dev=number.isBlank()?null:mapper.developer(number);
        if(dev==null)error(issues,row,"관리번호",number,"등록되지 않은 관리번호입니다. 개발자 풀에 먼저 등록해 주세요.");
        else {if(!"Y".equals(dev.get("use_yn")))error(issues,row,"관리번호",number,"사용을 중지한 사람입니다.");if(!name.equals(dev.get("dev_nm")))error(issues,row,"이름",name,"관리번호 "+number+"의 이름은 '"+dev.get("dev_nm")+"'입니다. 이름을 확인해 주세요.");}
        String emp=v.getOrDefault("구분","").isBlank()?(dev==null?null:(String)dev.get("emp_se_cd")):resolve("구분","EMP_TYPE",v.get("구분"),row,codes,mappings,issues,true);s.put("emp_se_cd",emp);
        if(dev!=null){s.put("dev_id",dev.get("dev_id"));for(String key:List.of("duty_se_cd","pstn_cd","bsn_part_id"))s.put(key,dev.get(key));if(!Objects.equals(emp,dev.get("emp_se_cd")))warning(issues,row,"구분",v.get("구분"),emp,"개발자 정보의 구분('"+dev.get("emp_se_cd")+"')과 다릅니다. 파일 값으로 저장합니다.");String affiliation=v.getOrDefault("소속","");if(!affiliation.isBlank()&&!Objects.equals(affiliation,dev.get("affiliation")))warning(issues,row,"소속",affiliation,null,"개발자 정보의 소속('"+dev.get("affiliation")+"')과 다릅니다. 확인만 합니다.");Object resigned=dev.get("rsgn_dt");if(resigned!=null&&LocalDate.parse(resigned.toString()).isBefore(YearMonth.parse(month).atDay(1)))warning(issues,row,"퇴사",resigned.toString(),null,"이 달 전에 퇴사한 사람입니다(퇴사일 "+resigned+").");}
        String[][] amounts={{"월 급여","slry_amt"},{"수당","alwnc_amt"},{"4대보험(회사)","sins_cmpny_amt"},{"4대보험(본인)","sins_emp_amt"},{"3.3% 원천세","whtax_amt"},{"근로소득세","ern_tax_amt"},{"퇴직금 적립","rtrpay_acml_amt"},{"부가세","vat_amt"},{"실지급액","excel_real_pay_amt"},{"개인별 원가","excel_indv_lbr_cst_amt"},{"팀비","team_expense_amt"},{"프로젝트경비","project_expense_amt"}};
        for(var a:amounts){String raw=v.getOrDefault(a[0],"");boolean ignored="OS".equals(emp)&&Set.of("팀비","프로젝트경비").contains(a[0]);if(ignored){s.put(a[1],0L);if(!raw.isBlank())warning(issues,row,a[0],raw,null,"외주는 팀비·프로젝트경비를 넣지 않아 무시했습니다.");}else s.put(a[1],money(raw,a[0],row,issues,a[1].equals("slry_amt")));}
        if("FT".equals(emp)&&v.getOrDefault("4대보험(회사)","").isBlank())error(issues,row,"4대보험(회사)","","정규직은 4대보험(회사)이 꼭 필요합니다.");
        try {
            if(Set.of("FL","OS").contains(emp==null?"":emp)){s.put("alwnc_amt",0L);s.put("rtrpay_acml_amt",0L);}
            else {if(s.get("alwnc_amt")==null){String attr=dev==null||dev.get("pstn_cd")==null?null:mapper.allowance((String)dev.get("pstn_cd"));s.put("alwnc_amt",attr==null||attr.isBlank()?0L:checked(new BigDecimal(attr)));}if("FT".equals(emp)&&s.get("rtrpay_acml_amt")==null&&s.get("slry_amt")!=null)s.put("rtrpay_acml_amt",checked(BigDecimal.valueOf((Long)s.get("slry_amt")).add(BigDecimal.valueOf((Long)s.get("alwnc_amt"))).divide(BigDecimal.valueOf(12),0,RoundingMode.HALF_UP)));}
            if(!"OS".equals(emp))s.put("vat_amt",0L);else if(s.get("vat_amt")==null&&s.get("slry_amt")!=null)s.put("vat_amt",checked(BigDecimal.valueOf((Long)s.get("slry_amt")).multiply(shared.effectiveParam("VAT_RATE",month,DataScope.ALL).numericValue()).divide(BigDecimal.valueOf(100),0,RoundingMode.HALF_UP)));
        }catch(NumberFormatException|ArithmeticException e){error(issues,row,"수당","","기준값 또는 계산 금액이 올바른 정수 범위를 벗어났습니다.");}
        for(var a:amounts)if(!a[1].startsWith("excel_")&&s.get(a[1])==null)s.put(a[1],0L);
        s.put("excel_diff_desc",null);
        if(issues.stream().noneMatch(i->i.row()==row&&i.severity().equals("ERROR"))){var computed=mapper.preview(s);String difference=compare(s,computed,row,issues);s.put("excel_diff_desc",difference);}
        return s;
    }
    static String compare(Map<String,Object> inputs,Map<String,Object> db,int row,List<Issue> issues){List<String> diffs=new ArrayList<>();for(String[] f:new String[][]{{"excel_real_pay_amt","real_pay_amt","실지급액"},{"excel_indv_lbr_cst_amt","indv_lbr_cst_amt","개인별 원가"}}){Object excel=inputs.get(f[0]);Object value=db.get(f[1]);if(excel!=null&&value!=null&&new BigDecimal(excel.toString()).compareTo(new BigDecimal(value.toString()))!=0){String msg="엑셀 값("+excel+")과 시스템 계산값("+value+")이 다릅니다.";warning(issues,row,f[2],excel.toString(),value.toString(),msg);diffs.add(f[2]+": "+msg);}}return diffs.isEmpty()?null:String.join(" / ",diffs);}
    private static long checked(BigDecimal value){long result=value.setScale(0,RoundingMode.UNNECESSARY).longValueExact();if(result<0)throw new ArithmeticException();return result;}
    private static Long money(String raw,String column,int row,List<Issue> issues,boolean required){if(raw.isBlank()){if(required)error(issues,row,column,raw,column+"가 비어 있습니다.");return null;}try{return checked(new BigDecimal(raw.replace(",","")));}catch(NumberFormatException|ArithmeticException e){error(issues,row,column,raw,column+"는 0 이상 정수로 적어 주세요.");return null;}}
    private static LocalDate date(String raw,String column,int row,List<Issue> issues){if(raw.isBlank())return null;try{return CalendarValues.date(raw,column);}catch(ApiException e){error(issues,row,column,raw,"올바른 날짜를 입력해 주세요.");return null;}}
    private static void text(Map<String,Object> d,Map<String,String> v,String column,String key,int max,boolean required,int row,List<Issue> issues){String raw=v.getOrDefault(column,"");if(required&&raw.isBlank())error(issues,row,column,raw,column+"이 비어 있습니다.");if(raw.length()>max)error(issues,row,column,raw,column+"는 "+max+"자까지 쓸 수 있습니다.");d.put(key,raw.isBlank()?null:raw);}
    private List<String> multiple(String raw,String column,String group,String separator,int row,List<UploadCode> codes,List<UploadMapping> mappings,List<Issue> issues){Set<String> result=new LinkedHashSet<>();for(String token:raw.split(separator)){if(token.isBlank())continue;String code=resolve(column,group,token.trim(),row,codes,mappings,issues,false);if(code!=null)result.add(code);}return List.copyOf(result);}
    private String resolve(String column,String group,String raw,int row,List<UploadCode> codes,List<UploadMapping> mappings,List<Issue> issues,boolean required){
        if(raw.isBlank()){if(required)error(issues,row,column,raw,column.equals("직원구분")?"직원구분은 정규직·계약직·프리랜서·외주 중 하나로 적어 주세요.":column+"가 비어 있습니다.");return null;}
        var options=codes.stream().filter(c->c.group().equals(group)).toList();for(var c:options)if(c.name().equals(raw))return c.code();for(var c:options)if(c.code().equals(raw))return c.code();
        Set<String> targets=new HashSet<>();mappings.stream().filter(m->m.group().equals(group)&&m.label().equals(raw)).forEach(m->targets.add(m.code()));
        if(group.equals("EMP_TYPE")&&targets.isEmpty()){if(raw.equals("업체"))targets.add("OS");if(raw.equals("정직원"))targets.add("FT");}
        if(targets.size()==1)for(var c:options)if(targets.contains(c.code())){warning(issues,row,column,raw,c.code(),"'"+raw+"'를 '"+c.name()+"'(으)로 바꿔 읽었습니다.");return c.code();}
        issues.add(new Issue(row,column,"ERROR","'"+raw+"'은(는) 등록된 "+column+"이 아닙니다. 목록에 있는 이름으로 적거나 [대응 등록]으로 연결해 주세요.",raw,null,true));return null;
    }
    static void error(List<Issue> issues,int row,String column,String raw,String message){issues.add(new Issue(row,column,"ERROR",message,raw,null,false));}
    static void warning(List<Issue> issues,int row,String column,String raw,String normalized,String message){issues.add(new Issue(row,column,"WARNING",message,raw,normalized,false));}
}
