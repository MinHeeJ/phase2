package com.sicost.uploadsops;

import java.io.*;
import java.util.List;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import static com.sicost.uploadsops.UploadModels.*;

public final class ExcelTemplateWriter {
    private ExcelTemplateWriter() {}
    public static byte[] write(List<Code> codes) {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            Sheet input=book.createSheet("거래처"),help=book.createSheet("입력 방법"),lists=book.createSheet("목록");
            input.createRow(0).createCell(0).setCellValue("* 표시는 필수입니다. 4행 제목은 바꾸지 마세요.");
            input.createRow(1).createCell(0).setCellValue("고객사·둘 다: 세금계산서 이메일 필수 / 협력사·둘 다: 은행·계좌번호·예금주 필수");
            CellStyle gray=book.createCellStyle();gray.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());gray.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle text=book.createCellStyle();text.setDataFormat(book.createDataFormat().getFormat("@"));
            Row header=input.createRow(3),example=input.createRow(4);
            for(int c=0;c<12;c++) {header.createCell(c).setCellValue(UploadForm.TITLES[c]+(c<3?"*":""));header.getCell(c).setCellStyle(gray);example.createCell(c).setCellStyle(gray);input.setColumnWidth(c,24*256);}
            example.getCell(1).setCellValue("예시) 이 행은 검사하지 않습니다");
            CellStyle grayText=book.createCellStyle();grayText.cloneStyleFrom(gray);grayText.setDataFormat(text.getDataFormat());
            input.setDefaultColumnStyle(2,text);input.setDefaultColumnStyle(9,text);example.getCell(2).setCellStyle(grayText);example.getCell(9).setCellStyle(grayText);input.createFreezePane(0,4);
            help.createRow(0).createCell(0).setCellValue("제목 아래에 데이터를 입력하고 xlsx로 저장하세요. 예시)로 시작하는 상호는 검사하지 않습니다.");
            help.createRow(1).createCell(0).setCellValue("오류가 하나라도 있으면 파일 전체를 반영하지 않습니다. 경고만 있으면 반영할 수 있습니다.");
            String[] groups={"PARTNER_KIND","INDUSTRY","BANK"};int[] targets={0,4,8};
            for(int g=0;g<groups.length;g++) {
                final String group=groups[g];List<Code> options=codes.stream().filter(c->c.group().equals(group)).toList();
                if(lists.getRow(0)==null)lists.createRow(0);lists.getRow(0).createCell(g).setCellValue(UploadForm.TITLES[targets[g]]);
                for(int r=0;r<options.size();r++){if(lists.getRow(r+1)==null)lists.createRow(r+1);lists.getRow(r+1).createCell(g).setCellValue(options.get(r).name());}
                if(!options.isEmpty()) {
                    Name name=book.createName();name.setNameName("Upload"+group);String col=String.valueOf((char)('A'+g));name.setRefersToFormula("'목록'!$"+col+"$2:$"+col+"$"+(options.size()+1));
                    var helper=input.getDataValidationHelper();var validation=helper.createValidation(helper.createFormulaListConstraint(name.getNameName()),new CellRangeAddressList(4,10004,targets[g],targets[g]));validation.setShowErrorBox(false);input.addValidationData(validation);
                }
            }
            lists.protectSheet(java.util.UUID.randomUUID().toString());book.write(out);return out.toByteArray();
        } catch(IOException ex){throw new UncheckedIOException(ex);}
    }
}
