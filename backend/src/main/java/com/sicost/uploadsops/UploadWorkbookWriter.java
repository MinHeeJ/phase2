package com.sicost.uploadsops;

import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import static com.sicost.uploadsops.UploadModels.*;

@Component
public class UploadWorkbookWriter {
    public byte[] template(List<UploadCode> codes)throws IOException {
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Sheet sheet=book.createSheet("거래처"),guide=book.createSheet("입력 방법"),lists=book.createSheet("목록");
            sheet.createRow(0).createCell(0).setCellValue("* 표시는 필수입니다. 4행 제목은 바꾸지 마세요.");
            sheet.createRow(1).createCell(0).setCellValue("고객사·둘 다: 세금계산서 이메일 필수 / 협력사·둘 다: 은행·계좌번호·예금주 필수");
            Row header=sheet.createRow(3),example=sheet.createRow(4);CellStyle grey=book.createCellStyle();grey.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());grey.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font bold=book.createFont();bold.setBold(true);CellStyle title=book.createCellStyle();title.cloneStyleFrom(grey);title.setFont(bold);
            CellStyle text=book.createCellStyle();text.setDataFormat(book.createDataFormat().getFormat("@"));
            for(int i=0;i<12;i++) {header.createCell(i).setCellValue(PartnerUploadForm.HEADERS.get(i)+(i<3?" *":""));header.getCell(i).setCellStyle(title);example.createCell(i).setCellStyle(grey);sheet.setColumnWidth(i,24*256);}
            example.getCell(1).setCellValue("예시) 이 행은 검사·반영에서 제외됩니다.");
            for(int i:new int[]{2,9}) {sheet.setDefaultColumnStyle(i,text);CellStyle sample=book.createCellStyle();sample.cloneStyleFrom(grey);sample.setDataFormat(text.getDataFormat());example.getCell(i).setCellStyle(sample);}
            sheet.createFreezePane(0,4);
            guide.createRow(0).createCell(0).setCellValue("4행 제목을 유지하고 5행 예시 아래에 입력하세요. 예시)로 시작하는 상호는 제외합니다.");
            guide.createRow(1).createCell(0).setCellValue("사업자번호는 숫자 10자리, 계좌번호는 숫자·하이픈 8~20자입니다. 오류가 있으면 파일 전체를 반영하지 않습니다.");
            int listColumn=0;
            for(String group:List.of("PARTNER_KIND","INDUSTRY","BANK")) {
                List<UploadCode> options=codes.stream().filter(c->c.group().equals(group)).toList();
                if(lists.getRow(0)==null)lists.createRow(0);lists.getRow(0).createCell(listColumn).setCellValue(group);
                for(int i=0;i<options.size();i++){Row r=lists.getRow(i+1);if(r==null)r=lists.createRow(i+1);r.createCell(listColumn).setCellValue(options.get(i).name());}
                if(!options.isEmpty()) {
                    String name="Upload_"+group;Name range=book.createName();range.setNameName(name);String letter=String.valueOf((char)('A'+listColumn));range.setRefersToFormula("'목록'!$"+letter+"$2:$"+letter+"$"+(options.size()+1));
                    DataValidationHelper helper=sheet.getDataValidationHelper();int target=listColumn==0?0:listColumn==1?4:8;
                    DataValidation validation=helper.createValidation(helper.createFormulaListConstraint(name),new CellRangeAddressList(4,10004,target,target));validation.setShowErrorBox(true);validation.setErrorStyle(DataValidation.ErrorStyle.STOP);sheet.addValidationData(validation);
                }
                lists.setColumnWidth(listColumn,24*256);listColumn++;
            }
            lists.protectSheet(UUID.randomUUID().toString());book.write(out);return out.toByteArray();
        }
    }
    public byte[] errorFile(InputStream input,List<UploadIssue> issues)throws IOException {
        return errorFile(input,issues,"B.7");
    }
    public byte[] errorFile(InputStream input,List<UploadIssue> issues,String form)throws IOException {
        try(XSSFWorkbook book=new XSSFWorkbook(input);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Sheet original=book.getSheetAt(0);while(book.getNumberOfSheets()>1)book.removeSheetAt(1);
            FormulaEvaluator evaluator=book.getCreationHelper().createFormulaEvaluator();Row header="B.7".equals(form)?PartnerUploadForm.header(original,evaluator):PersonnelUploadForm.title(original,form);
            List<String> headings="B.7".equals(form)?PartnerUploadForm.HEADERS:PersonnelUploadForm.headers(form);
            Map<Integer,Integer> columns=new HashMap<>();
            if("B.7".equals(form))columns.putAll(PartnerUploadForm.columns(header,evaluator));
            else for(Cell cell:header)if(cell.getCellType()==CellType.STRING){int index=headings.indexOf(PersonnelUploadForm.label(cell.getStringCellValue(),form));if(index>=0)columns.put(index,cell.getColumnIndex());}
            int end=0;for(Row row:original)end=Math.max(end,row.getLastCellNum());
            Cell heading=header.getCell(end,Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);heading.setCellValue("오류 사유");original.setColumnWidth(end,60*256);
            Map<Integer,List<String>> reasons=new LinkedHashMap<>();Map<Short,CellStyle> styles=new HashMap<>();
            for(UploadIssue issue:issues) {
                reasons.computeIfAbsent(issue.rowNo(),k->new ArrayList<>()).add(issue.message());
                if(!issue.severity().equals("ERROR"))continue;
                Row row=original.getRow(issue.rowNo()-1);Integer column=columns.get(headings.indexOf(issue.column()));
                if(row!=null&&column!=null){Cell cell=row.getCell(column,Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);short base=cell.getCellStyle().getIndex();CellStyle style=styles.computeIfAbsent(base,k->{CellStyle s=book.createCellStyle();s.cloneStyleFrom(cell.getCellStyle());s.setFillForegroundColor(IndexedColors.ROSE.getIndex());s.setFillPattern(FillPatternType.SOLID_FOREGROUND);return s;});cell.setCellStyle(style);}
            }
            for(var entry:reasons.entrySet()){Row row=original.getRow(entry.getKey()-1);if(row!=null)row.createCell(end).setCellValue(String.join(" / ",entry.getValue()));}
            Sheet list=book.createSheet("오류 목록");Row title=list.createRow(0);String[] labels={"구분","행","열","입력값","이유"};for(int i=0;i<labels.length;i++)title.createCell(i).setCellValue(labels[i]);
            int n=1;for(UploadIssue issue:issues){Row row=list.createRow(n++);row.createCell(0).setCellValue(issue.severity());row.createCell(1).setCellValue(issue.rowNo());row.createCell(2).setCellValue(issue.column());row.createCell(3).setCellValue(issue.rawValue());row.createCell(4).setCellValue(issue.message());}
            book.write(out);return out.toByteArray();
        }
    }
    public byte[] template(String form,List<UploadCode> codes)throws IOException {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            boolean developer=form.equals("B.8"),attendance=form.equals("B.3");Sheet sheet=book.createSheet(attendance?"파견 근태":developer?"개발자":"급여"),guide=book.createSheet("입력 방법"),lists=book.createSheet("목록");
            sheet.createRow(0).createCell(0).setCellValue("* 표시는 필수입니다. 4행 제목은 바꾸지 마세요.");
            sheet.createRow(1).createCell(0).setCellValue(attendance?"선택 연월·프로젝트ID와 일치해야 합니다. 해당 월 근무일수는 직접 입력하세요.":developer?"관리번호를 비우면 자동 발급합니다. 외주는 사용 중 협력사가 필요합니다.":"연월은 업로드 창에서 선택합니다. 같은 달의 급여 전체를 새 파일로 바꿉니다.");
            var headers=PersonnelUploadForm.headers(form);Row title=sheet.createRow(3),example=sheet.createRow(4);CellStyle text=book.createCellStyle();text.setDataFormat(book.createDataFormat().getFormat("@"));
            Set<String> required=attendance?Set.of("연월","프로젝트ID","관리번호","이름","해당 월 근무일수"):developer?Set.of("이름","직원구분","주 직무"):Set.of("관리번호","이름","월 급여");
            for(int i=0;i<headers.size();i++){String h=headers.get(i);title.createCell(i).setCellValue(h+(required.contains(h)?" *":""));example.createCell(i);sheet.setColumnWidth(i,24*256);if(h.equals("관리번호"))sheet.setDefaultColumnStyle(i,text);}
            example.getCell(headers.indexOf("이름")).setCellValue("예시) 이 행은 검사·반영에서 제외됩니다.");sheet.createFreezePane(0,4);
            guide.createRow(0).createCell(0).setCellValue("4행 제목을 유지하고 5행 예시 아래에 입력하세요. 오류가 하나라도 있으면 파일 전체를 반영하지 않습니다.");
            guide.createRow(1).createCell(0).setCellValue(attendance?"DAY는 근무일수, HOUR는 정규 근무시간 필수. 빈 초과 근무시간은 0, 수식은 금지합니다.":developer?"보조 직무는 쉼표·/ 구분, 스킬은 쉼표 구분. 날짜는 YYYY-MM-DD, 관리번호는 텍스트입니다.":"B~W 열 위치를 유지하세요. X 관리번호, Y 부가세, Z 근로소득세. 계산값은 비교만 합니다.");
            Map<String,String> groups=attendance?Map.of():developer?Map.of("EMP_TYPE","직원구분","POSITION","직위","WORK_TYPE","업무 구분","GRADE","등급","JOB","주 직무","SKILL","스킬","WORK_STATUS","상태"):Map.of("EMP_TYPE","구분");int col=0;
            for(var entry:groups.entrySet()){var options=codes.stream().filter(c->c.group().equals(entry.getKey())).toList();if(lists.getRow(0)==null)lists.createRow(0);lists.getRow(0).createCell(col).setCellValue(entry.getKey());for(int i=0;i<options.size();i++){Row r=lists.getRow(i+1);if(r==null)r=lists.createRow(i+1);r.createCell(col).setCellValue(options.get(i).name());}
                if(!options.isEmpty()){Name range=book.createName();range.setNameName("Personnel_"+entry.getKey());String letter=org.apache.poi.ss.util.CellReference.convertNumToColString(col);range.setRefersToFormula("'목록'!$"+letter+"$2:$"+letter+"$"+(options.size()+1));var helper=sheet.getDataValidationHelper();int target=headers.indexOf(entry.getValue());var validation=helper.createValidation(helper.createFormulaListConstraint(range.getNameName()),new CellRangeAddressList(5,10004,target,target));validation.setShowErrorBox(true);sheet.addValidationData(validation);}col++;}
            lists.protectSheet(UUID.randomUUID().toString());book.write(out);return out.toByteArray();
        }
    }
}
