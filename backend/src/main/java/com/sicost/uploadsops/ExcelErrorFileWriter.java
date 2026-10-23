package com.sicost.uploadsops;

import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import static com.sicost.uploadsops.UploadModels.*;

final class ExcelErrorFileWriter {
    private ExcelErrorFileWriter() {}
    static byte[] write(XSSFWorkbook source,List<Issue> issues)throws IOException {
        try(SXSSFWorkbook book=new SXSSFWorkbook(new XSSFWorkbook(),100,true,true);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            try {
                Sheet original=source.getSheetAt(0),sheet=book.createSheet(original.getSheetName().equals("오류 목록")?"거래처":original.getSheetName());
                int header=PartnerUploadForm.findHeader(original);Map<Integer,Integer> columns=PartnerUploadForm.columns(original,header);
                int end=0;for(Row row:original)end=Math.max(end,row.getLastCellNum());
                if(end>=16384)throw PartnerUploadForm.fileError("UNSUPPORTED_FILE_TYPE","오류 사유 열을 추가할 수 없습니다.");
                Map<Integer,CellStyle> styles=new HashMap<>();
                CellStyle red=book.createCellStyle();red.setFillForegroundColor(IndexedColors.ROSE.getIndex());red.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                for(Row row:original) {
                    Row copy=sheet.createRow(row.getRowNum());copy.setHeight(row.getHeight());
                    List<Issue> rowIssues=issues.stream().filter(i->i.rowNo()==row.getRowNum()+1).toList();
                    for(Cell cell:row) {
                        Cell target=copy.createCell(cell.getColumnIndex());
                        switch(cell.getCellType()) {
                            case STRING -> target.setCellValue(cell.getStringCellValue());
                            case NUMERIC -> target.setCellValue(cell.getNumericCellValue());
                            case BOOLEAN -> target.setCellValue(cell.getBooleanCellValue());
                            case FORMULA -> target.setCellFormula(cell.getCellFormula());
                            case ERROR -> target.setCellErrorValue(cell.getErrorCellValue());
                            default -> target.setBlank();
                        }
                        CellStyle style=styles.computeIfAbsent((int)cell.getCellStyle().getIndex(),k->{CellStyle s=book.createCellStyle();s.cloneStyleFrom(cell.getCellStyle());return s;});
                        target.setCellStyle(style);
                    }
                    for(Issue issue:rowIssues)if("ERROR".equals(issue.severity())) {
                        int index=PartnerUploadForm.headerIndex(issue.column());Integer col=columns.get(index);
                        if(col!=null) {Cell target=copy.getCell(col);if(target==null)target=copy.createCell(col);CellStyle highlight=book.createCellStyle();highlight.cloneStyleFrom(target.getCellStyle());highlight.setFillForegroundColor(IndexedColors.ROSE.getIndex());highlight.setFillPattern(FillPatternType.SOLID_FOREGROUND);target.setCellStyle(highlight);}
                    }
                    if(row.getRowNum()==header)copy.createCell(end).setCellValue("오류 사유");
                    else if(!rowIssues.isEmpty())copy.createCell(end).setCellValue(String.join("\n",rowIssues.stream().map(Issue::message).toList()).substring(0,Math.min(32767,String.join("\n",rowIssues.stream().map(Issue::message).toList()).length())));
                }
                for(int c=0;c<end;c++)sheet.setColumnWidth(c,original.getColumnWidth(c));sheet.setColumnWidth(end,70*256);sheet.createFreezePane(0,header+1);
                Sheet errors=book.createSheet("오류 목록");String[] titles={"구분","행","열","입력값","이유"};Row h=errors.createRow(0);for(int c=0;c<titles.length;c++)h.createCell(c).setCellValue(titles[c]);
                int n=1;for(Issue issue:issues) {Row row=errors.createRow(n++);row.createCell(0).setCellValue(issue.severity());row.createCell(1).setCellValue(issue.rowNo());row.createCell(2).setCellValue(issue.column());row.createCell(3).setCellValue(issue.rawValue()==null?"":issue.rawValue());row.createCell(4).setCellValue(issue.message());}
                book.write(out);return out.toByteArray();
            } finally {book.dispose();}
        }
    }
}
