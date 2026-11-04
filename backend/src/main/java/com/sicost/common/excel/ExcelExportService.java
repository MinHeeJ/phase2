package com.sicost.common.excel;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class ExcelExportService {
    public record ExportColumn<T>(String key, String title, boolean sensitive, boolean numeric, Function<T,Object> value) {}
    public record ExportSheet<T>(String name, List<ExportColumn<T>> columns, List<T> rows) {}

    public <T> byte[] create(String conditions, String actor, LocalDateTime now,
                            List<ExportColumn<T>> columns, List<T> rows) {
        return workbook(conditions,actor,now,List.of(new ExportSheet<>("목록",columns,rows)),false);
    }

    public byte[] createSheets(String conditions, String actor, LocalDateTime now,
                               List<ExportSheet<?>> sheets) {
        if (sheets==null || sheets.isEmpty()) throw new IllegalArgumentException("엑셀 시트가 필요합니다.");
        for (var sheet:sheets) org.apache.poi.ss.util.WorkbookUtil.validateSheetName(sheet.name());
        return workbook(conditions,actor,now,sheets,true);
    }

    private byte[] workbook(String conditions,String actor,LocalDateTime now,List<ExportSheet<?>> sheets,boolean typed) {
        try (var workbook=new XSSFWorkbook(); var output=new ByteArrayOutputStream()) {
            var titleStyle=workbook.createCellStyle();
            titleStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());titleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var font=workbook.createFont();font.setBold(true);titleStyle.setFont(font);
            var textStyle=workbook.createCellStyle();textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            var numberStyle=workbook.createCellStyle();
            numberStyle.setDataFormat(workbook.createDataFormat().getFormat(typed?"#,##0.###############":"#,##0"));
            var styles=new Styles(titleStyle,textStyle,numberStyle,typed);
            for (var definition:sheets) {
                var sheet=workbook.createSheet(definition.name());
                sheet.createRow(0).createCell(0).setCellValue("조회 조건: "+conditions);
                sheet.createRow(1).createCell(0).setCellValue("내려받은 사람: "+actor+" · "+now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                writeSheet(sheet,definition,styles);
            }
            workbook.write(output);return output.toByteArray();
        } catch(IOException e) {throw new IllegalStateException("엑셀 파일 생성에 실패했습니다.",e);}
    }

    private record Styles(CellStyle title,CellStyle text,CellStyle number,boolean typed) {}

    private <T> void writeSheet(Sheet sheet,ExportSheet<T> definition,Styles styles) {
        var columns=definition.columns();
        var rows=definition.rows();
        var header=sheet.createRow(3);
        for(int i=0;i<columns.size();i++) {
            var cell=header.createCell(i);cell.setCellValue(columns.get(i).title());cell.setCellStyle(styles.title());sheet.setColumnWidth(i,24*256);
        }
        sheet.createFreezePane(0,4);
        for(int r=0;r<rows.size();r++) {
            var row=sheet.createRow(r+4);
            for(int c=0;c<columns.size();c++) {
                var col=columns.get(c);Object value=col.value().apply(rows.get(r));var cell=row.createCell(c);
                if (styles.typed() && value==null) cell.setBlank();
                else if (styles.typed() && value instanceof Boolean flag) cell.setCellValue(flag);
                else if(col.numeric() && value instanceof Number number) {cell.setCellValue(number.doubleValue());cell.setCellStyle(styles.number());}
                else {cell.setCellValue(value==null?"":value.toString());cell.setCellStyle(styles.text());}
            }
        }
    }
}
