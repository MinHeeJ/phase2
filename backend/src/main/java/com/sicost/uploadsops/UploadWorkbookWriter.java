package com.sicost.uploadsops;

import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;
import static com.sicost.uploadsops.UploadModels.*;

@Component
public class UploadWorkbookWriter {
    public byte[] template(List<UploadCode> codes)throws IOException {
        var book=new SXSSFWorkbook(100);
        book.setCompressTempFiles(true);
        try(book;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Sheet sheet=book.createSheet("거래처"),guide=book.createSheet("입력 방법"),lists=book.createSheet("목록");
            sheet.createRow(0).createCell(0).setCellValue("* 표시는 필수입니다. 4행 제목은 바꾸지 마세요.");
            sheet.createRow(1).createCell(0).setCellValue("고객사·둘 다: 세금계산서 이메일 필수 / 협력사·둘 다: 은행·계좌번호·예금주 필수");
            Row header=sheet.createRow(3),example=sheet.createRow(4);CellStyle grey=book.createCellStyle();grey.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());grey.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font bold=book.createFont();bold.setBold(true);CellStyle title=book.createCellStyle();title.cloneStyleFrom(grey);title.setFont(bold);
            CellStyle text=book.createCellStyle();text.setDataFormat(book.createDataFormat().getFormat("@"));
            for(int i=0;i<12;i++) {header.createCell(i).setCellValue(PartnerUploadForm.HEADERS.get(i)+(i<3?" *":""));header.getCell(i).setCellStyle(title);example.createCell(i).setCellStyle(grey);sheet.setColumnWidth(i,24*256);}
            example.getCell(1).setCellValue("예시) 이 행은 검사·반영에서 제외됩니다.");
            for(int i:new int[]{2,6,9}) {sheet.setDefaultColumnStyle(i,text);CellStyle sample=book.createCellStyle();sample.cloneStyleFrom(grey);sample.setDataFormat(text.getDataFormat());example.getCell(i).setCellStyle(sample);}
            sheet.createFreezePane(0,4);
            guide.createRow(0).createCell(0).setCellValue("4행 제목을 유지하고 5행 예시 아래에 입력하세요. 예시)로 시작하는 상호는 제외합니다.");
            guide.createRow(1).createCell(0).setCellValue("사업자번호는 숫자 10자리, 계좌번호는 숫자·하이픈 8~20자입니다. 오류가 있으면 파일 전체를 반영하지 않습니다.");
            List<String> groups=List.of("PARTNER_KIND","INDUSTRY","BANK");
            List<List<UploadCode>> optionsByGroup=groups.stream().map(group->codes.stream().filter(c->c.group().equals(group)).toList()).toList();
            Row listHeader=lists.createRow(0);
            for(int column=0;column<groups.size();column++)listHeader.createCell(column).setCellValue(groups.get(column));
            int optionRows=optionsByGroup.stream().mapToInt(List::size).max().orElse(0);
            for(int i=0;i<optionRows;i++) {
                Row row=lists.createRow(i+1);
                for(int column=0;column<groups.size();column++) {
                    List<UploadCode> options=optionsByGroup.get(column);
                    if(i<options.size())row.createCell(column).setCellValue(options.get(i).name());
                }
            }
            for(int listColumn=0;listColumn<groups.size();listColumn++) {
                String group=groups.get(listColumn);List<UploadCode> options=optionsByGroup.get(listColumn);

                if(!options.isEmpty()) {
                    String name="Upload_"+group;Name range=book.createName();range.setNameName(name);String letter=String.valueOf((char)('A'+listColumn));range.setRefersToFormula("'목록'!$"+letter+"$2:$"+letter+"$"+(options.size()+1));
                    DataValidationHelper helper=sheet.getDataValidationHelper();int target=listColumn==0?0:listColumn==1?4:8;
                    DataValidation validation=helper.createValidation(helper.createFormulaListConstraint(name),new CellRangeAddressList(4,10004,target,target));validation.setShowErrorBox(true);validation.setErrorStyle(DataValidation.ErrorStyle.STOP);sheet.addValidationData(validation);
                }
                lists.setColumnWidth(listColumn,24*256);
            }
            lists.protectSheet(UUID.randomUUID().toString());book.write(out);return out.toByteArray();
        } finally {book.dispose();}
    }
    public byte[] errorFile(InputStream input,List<UploadIssue> issues)throws IOException {
        try(XSSFWorkbook book=new XSSFWorkbook(input);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Sheet original=book.getSheetAt(0);while(book.getNumberOfSheets()>1)book.removeSheetAt(1);
            FormulaEvaluator evaluator=book.getCreationHelper().createFormulaEvaluator();Row header=PartnerUploadForm.header(original,evaluator);Map<Integer,Integer> columns=PartnerUploadForm.columns(header,evaluator);
            int end=0;for(Row row:original)end=Math.max(end,row.getLastCellNum());
            Cell heading=header.getCell(end,Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);heading.setCellValue("오류 사유");original.setColumnWidth(end,60*256);
            Map<Integer,List<String>> reasons=new LinkedHashMap<>();Map<Short,CellStyle> styles=new HashMap<>();
            for(UploadIssue issue:issues) {
                reasons.computeIfAbsent(issue.rowNo(),k->new ArrayList<>()).add(issue.message());
                if(!issue.severity().equals("ERROR"))continue;
                Row row=original.getRow(issue.rowNo()-1);Integer column=columns.get(PartnerUploadForm.HEADERS.indexOf(issue.column()));
                if(row!=null&&column!=null){Cell cell=row.getCell(column,Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);short base=cell.getCellStyle().getIndex();CellStyle style=styles.computeIfAbsent(base,k->{CellStyle s=book.createCellStyle();s.cloneStyleFrom(cell.getCellStyle());s.setFillForegroundColor(IndexedColors.ROSE.getIndex());s.setFillPattern(FillPatternType.SOLID_FOREGROUND);return s;});cell.setCellStyle(style);}
            }
            for(var entry:reasons.entrySet()){Row row=original.getRow(entry.getKey()-1);if(row!=null)row.createCell(end).setCellValue(String.join(" / ",entry.getValue()));}
            // Edit the retained sheet before wrapping it: SXSSF cannot revisit existing rows.
            SXSSFWorkbook streaming=new SXSSFWorkbook(book,100,true,true);
            try(streaming) {
                Sheet list=streaming.createSheet("오류 목록");Row title=list.createRow(0);String[] labels={"구분","행","열","입력값","이유"};for(int i=0;i<labels.length;i++)title.createCell(i).setCellValue(labels[i]);
                int n=1;for(UploadIssue issue:issues){Row row=list.createRow(n++);row.createCell(0).setCellValue(issue.severity());row.createCell(1).setCellValue(issue.rowNo());row.createCell(2).setCellValue(issue.column());row.createCell(3).setCellValue(issue.rawValue());row.createCell(4).setCellValue(issue.message());}
                streaming.write(out);return out.toByteArray();
            } finally {streaming.dispose();}
        }
    }
}
