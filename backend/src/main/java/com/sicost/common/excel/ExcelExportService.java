package com.sicost.common.excel;

import com.sicost.common.security.Sensitive;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class ExcelExportService {
    public record ExportColumn<T>(String key, String title, boolean sensitive, boolean numeric, Function<T,Object> value) {}
    public static <T> ExportColumn<T> column(Class<?> source, String key, String title,
                                              boolean numeric, Function<T,Object> value) {
        boolean sensitive=source.isRecord() && Arrays.stream(source.getRecordComponents())
            .anyMatch(component->component.getName().equals(key) && component.isAnnotationPresent(Sensitive.class));
        return new ExportColumn<>(key,title,sensitive,numeric,value);
    }
    public <T> byte[] create(String conditions, String actor, LocalDateTime now,
                            List<ExportColumn<T>> columns, List<T> rows) {
        var workbook=new SXSSFWorkbook(100);
        workbook.setCompressTempFiles(true);
        try (workbook; var output=new ByteArrayOutputStream()) {
            var sheet=workbook.createSheet("목록");
            sheet.createRow(0).createCell(0).setCellValue("조회 조건: "+conditions);
            sheet.createRow(1).createCell(0).setCellValue("내려받은 사람: "+actor+" · "+now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            var titleStyle=workbook.createCellStyle();
            titleStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());titleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var font=workbook.createFont();font.setBold(true);titleStyle.setFont(font);
            var textStyle=workbook.createCellStyle();textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            var numberStyle=workbook.createCellStyle();numberStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
            var header=sheet.createRow(3);
            for(int i=0;i<columns.size();i++) {
                var cell=header.createCell(i);cell.setCellValue(columns.get(i).title());cell.setCellStyle(titleStyle);sheet.setColumnWidth(i,24*256);
            }
            sheet.createFreezePane(0,4);
            for(int r=0;r<rows.size();r++) {
                var row=sheet.createRow(r+4);
                for(int c=0;c<columns.size();c++) {
                    var col=columns.get(c);Object value=col.value().apply(rows.get(r));var cell=row.createCell(c);
                    if(col.numeric() && value instanceof Number number) {cell.setCellValue(number.doubleValue());cell.setCellStyle(numberStyle);}
                    else {cell.setCellValue(value==null?"":value.toString());cell.setCellStyle(textStyle);}
                }
            }
            workbook.write(output);return output.toByteArray();
        } catch(IOException e) {throw new IllegalStateException("엑셀 파일 생성에 실패했습니다.",e);}
        finally {workbook.dispose();}
    }
}
