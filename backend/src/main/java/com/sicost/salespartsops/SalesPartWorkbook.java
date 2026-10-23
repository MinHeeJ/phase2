package com.sicost.salespartsops;

import com.sicost.common.api.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import static com.sicost.salespartsops.SalesPartActionModels.*;

final class SalesPartWorkbook {
    private SalesPartWorkbook() {}
    private static final Map<String,String> LABELS=Map.ofEntries(
        Map.entry("bsn_part_nm","파트명"),Map.entry("ldr_nm","파트장"),Map.entry("prft_dstrb_rt","이익 배분율"),
        Map.entry("bsn_fee_amt","영업비(1인·월)"),Map.entry("aply_bgn_ym","적용 시작"),Map.entry("scheduledChanges","예정된 변경"),
        Map.entry("peopleCount","소속 인력"),Map.entry("projectCount","진행 프로젝트"),Map.entry("use_yn","사용 여부"));
    static void validateColumns(List<String> columns) {
        if(columns==null || columns.isEmpty() || new HashSet<>(columns).size()!=columns.size() || columns.stream().anyMatch(k->!LABELS.containsKey(k)))
            throw ApiException.badRequest("내려받을 열을 확인하세요.","columns");
    }
    static byte[] write(List<Detail> rows,List<String> columns,String actor,LocalDateTime now) {
        validateColumns(columns);
        SXSSFWorkbook book=new SXSSFWorkbook(100);
        try(book;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            Sheet sheet=book.createSheet("영업 파트");
            sheet.createRow(0).createCell(0).setCellValue("조회 조건: 전체" );
            sheet.createRow(1).createCell(0).setCellValue("내려받은 사람: "+actor+" · "+now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            CellStyle header=book.createCellStyle();header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font font=book.createFont();font.setBold(true);header.setFont(font);
            CellStyle money=book.createCellStyle();money.setDataFormat(book.createDataFormat().getFormat("#,##0"));
            Row title=sheet.createRow(3);
            for(int c=0;c<columns.size();c++) {Cell cell=title.createCell(c);cell.setCellValue(LABELS.get(columns.get(c)));cell.setCellStyle(header);sheet.setColumnWidth(c,24*256);}
            sheet.createFreezePane(0,4);
            int index=4;
            for(Detail detail:rows) {
                Row row=sheet.createRow(index++);Standard s=detail.currentStandard();
                for(int c=0;c<columns.size();c++) {
                    String key=columns.get(c);Cell cell=row.createCell(c);
                    Object value=switch(key) {
                        case "bsn_part_nm"->detail.bsn_part_nm();case "use_yn"->detail.use_yn().equals("Y")?"사용":"중지";
                        case "peopleCount"->detail.peopleCount();case "projectCount"->detail.projectCount();
                        case "scheduledChanges"->detail.scheduledChanges().stream().map(v->v.aply_bgn_ym()+" / "+Objects.toString(v.ldr_nm(),"")+" / "+v.prft_dstrb_rt()+"% / "+v.bsn_fee_amt()+"원").collect(Collectors.joining("; "));
                        case "ldr_nm"->s==null?null:s.ldr_nm();case "prft_dstrb_rt"->s==null?null:s.prft_dstrb_rt();
                        case "bsn_fee_amt"->s==null?null:s.bsn_fee_amt();default->s==null?null:s.aply_bgn_ym();
                    };
                    if(value instanceof Number number) {cell.setCellValue(number.doubleValue());if(key.equals("bsn_fee_amt"))cell.setCellStyle(money);}
                    else if(value!=null) cell.setCellValue(value.toString());
                }
            }
            book.write(out);return out.toByteArray();
        } catch(IOException e) {throw new IllegalStateException("파일을 생성할 수 없습니다.",e);}
        finally {book.dispose();}
    }
}
