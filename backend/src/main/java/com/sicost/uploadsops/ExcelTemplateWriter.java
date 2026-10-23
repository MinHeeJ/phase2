package com.sicost.uploadsops;

import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import static com.sicost.uploadsops.UploadModels.*;

final class ExcelTemplateWriter {
    private ExcelTemplateWriter() {}
    static byte[] write(List<Code> codes)throws IOException {
        try(SXSSFWorkbook book=new SXSSFWorkbook(new XSSFWorkbook(),100,true,true);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            try {
                Sheet input=book.createSheet("거래처"), help=book.createSheet("입력 방법"), list=book.createSheet("목록");
                input.createRow(0).createCell(0).setCellValue("* 표시는 필수입니다. 4행 제목은 바꾸지 마세요.");
                input.createRow(1).createCell(0).setCellValue("고객사·둘 다: 세금계산서 이메일 필수 / 협력사·둘 다: 은행·계좌번호·예금주 필수");
                CellStyle heading=book.createCellStyle();heading.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                Font bold=book.createFont();bold.setBold(true);heading.setFont(bold);
                CellStyle example=book.createCellStyle();example.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());example.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                CellStyle text=book.createCellStyle();text.setDataFormat(book.createDataFormat().getFormat("@"));
                Row titles=input.createRow(3),sample=input.createRow(4);
                for(int c=0;c<12;c++) {
                    Cell cell=titles.createCell(c);cell.setCellValue(PartnerUploadForm.HEADERS[c]+(c<3?" *":""));cell.setCellStyle(heading);
                    Cell value=sample.createCell(c);value.setCellStyle(example);if(c==1)value.setCellValue("예시) 입력할 때 이 행은 건너뜁니다.");
                    input.setColumnWidth(c,c==7?32*256:22*256);
                }
                input.setDefaultColumnStyle(2,text);input.setDefaultColumnStyle(9,text);input.createFreezePane(0,4);
                help.createRow(0).createCell(0).setCellValue("4행 제목은 유지하고 6행부터 입력하세요. 상호가 예시)로 시작하는 행은 제외합니다.");
                help.createRow(1).createCell(0).setCellValue("사업자번호는 숫자 10자리, 계좌번호는 숫자와 하이픈 8~20자입니다. 오류가 있으면 전체 반영하지 않습니다.");
                String[] groups={"PARTNER_KIND","INDUSTRY","BANK"};int[] target={0,4,8};
                for(int g=0;g<groups.length;g++) {
                    final String group=groups[g];List<Code> options=codes.stream().filter(c->c.group().equals(group)).toList();
                    Row h=list.getRow(0);if(h==null)h=list.createRow(0);h.createCell(g).setCellValue(PartnerUploadForm.HEADERS[target[g]]);
                    for(int i=0;i<options.size();i++) {Row row=list.getRow(i+1);if(row==null)row=list.createRow(i+1);row.createCell(g).setCellValue(options.get(i).name());}
                    if(!options.isEmpty()) {
                        Name range=book.createName();range.setNameName("PartnerUpload"+group);
                        String col=org.apache.poi.ss.util.CellReference.convertNumToColString(g);range.setRefersToFormula("'목록'!$"+col+"$2:$"+col+"$"+(options.size()+1));
                        var helper=input.getDataValidationHelper();var constraint=helper.createFormulaListConstraint(range.getNameName());
                        var validation=helper.createValidation(constraint,new CellRangeAddressList(4,10004,target[g],target[g]));validation.setShowErrorBox(true);input.addValidationData(validation);
                    }
                    list.setColumnWidth(g,25*256);
                }
                list.protectSheet(UUID.randomUUID().toString());book.write(out);return out.toByteArray();
            } finally {book.dispose();}
        }
    }
}
