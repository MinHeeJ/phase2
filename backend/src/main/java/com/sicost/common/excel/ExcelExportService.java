package com.sicost.common.excel;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class ExcelExportService {
    public record ExportColumn(String key,String label,boolean numeric,boolean sensitive) {}
    public byte[] write(List<ExportColumn> columns,List<Map<String,Object>> rows,String conditions,
                        String actor,LocalDateTime now) {
        SXSSFWorkbook book=new SXSSFWorkbook(100);
        try(book;var out=new ByteArrayOutputStream()) {
            Sheet sheet=book.createSheet("목록");
            sheet.createRow(0).createCell(0).setCellValue("조회 조건: "+conditions);
            sheet.createRow(1).createCell(0).setCellValue("내려받은 사람: "+actor+" · "+now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            Font bold=book.createFont();bold.setBold(true);
            CellStyle heading=book.createCellStyle();heading.setFont(bold);
            heading.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle text=book.createCellStyle();text.setDataFormat(book.createDataFormat().getFormat("@"));
            CellStyle number=book.createCellStyle();number.setDataFormat(book.createDataFormat().getFormat("#,##0"));
            Row header=sheet.createRow(3);
            for(int i=0;i<columns.size();i++) {Cell c=header.createCell(i);c.setCellValue(columns.get(i).label());c.setCellStyle(heading);sheet.setColumnWidth(i,24*256);}
            sheet.createFreezePane(0,4);
            int index=4;
            for(Map<String,Object> data:rows) {
                Row row=sheet.createRow(index++);
                for(int i=0;i<columns.size();i++) {
                    ExportColumn col=columns.get(i);Object value=data.get(col.key());Cell cell=row.createCell(i);
                    if(col.numeric() && value instanceof Number n) {cell.setCellValue(n.doubleValue());cell.setCellStyle(number);}
                    else {cell.setCellValue(value==null?"":value.toString());cell.setCellStyle(text);}
                }
            }
            book.write(out);return out.toByteArray();
        } catch(IOException ex) {throw new UncheckedIOException("엑셀 파일 생성에 실패했습니다.",ex);}
        finally {book.dispose();}
    }
}
