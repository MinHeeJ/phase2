package com.sicost.common.excel;

import com.sicost.common.security.Sensitive;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class ExcelExportService {
    public record Column(String key, String title, boolean numeric, boolean sensitive) {
        public static Column marked(String key,String title,Class<?> source) {
            boolean sensitive=Arrays.stream(source.getRecordComponents()).anyMatch(c->c.getName().equals(key)&&c.isAnnotationPresent(Sensitive.class));
            return new Column(key,title,false,sensitive);
        }
    }
    public byte[] workbook(List<Column> columns,List<Map<String,Object>> rows,String conditions,String actor,LocalDateTime time) {
        try(var book=new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
            Sheet sheet=book.createSheet("목록");
            sheet.createRow(0).createCell(0).setCellValue("조회 조건: "+conditions);
            sheet.createRow(1).createCell(0).setCellValue("내려받은 사람: "+actor+" · "+time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            CellStyle heading=book.createCellStyle();heading.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font bold=book.createFont();bold.setBold(true);heading.setFont(bold);
            CellStyle text=book.createCellStyle();text.setDataFormat(book.createDataFormat().getFormat("@"));
            CellStyle number=book.createCellStyle();number.setDataFormat(book.createDataFormat().getFormat("#,##0"));
            Row header=sheet.createRow(3);
            for(int c=0;c<columns.size();c++) {Cell cell=header.createCell(c);cell.setCellValue(columns.get(c).title());cell.setCellStyle(heading);sheet.setColumnWidth(c,22*256);}
            sheet.createFreezePane(0,4);
            int r=4;
            for(Map<String,Object> values:rows) {
                Row row=sheet.createRow(r++);
                for(int c=0;c<columns.size();c++) {
                    Column column=columns.get(c);Object value=values.get(column.key());Cell cell=row.createCell(c);
                    if(column.numeric() && value instanceof Number n) {cell.setCellValue(n.doubleValue());cell.setCellStyle(number);}
                    else {cell.setCellValue(value==null?"":value.toString());cell.setCellStyle(text);}
                }
            }
            book.write(output);return output.toByteArray();
        } catch(IOException ex) {throw new UncheckedIOException(ex);}
    }
}
