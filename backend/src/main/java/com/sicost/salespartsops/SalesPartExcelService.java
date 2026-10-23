package com.sicost.salespartsops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.*;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.salespartsops.SalesPartModels.*;

@Service
public class SalesPartExcelService {
    private static final Map<String,String> TITLES=Map.ofEntries(
        Map.entry("bsn_part_nm","파트명"),Map.entry("ldr_nm","파트장"),Map.entry("prft_dstrb_rt","이익 배분율"),
        Map.entry("bsn_fee_amt","영업비(1인·월)"),Map.entry("aply_bgn_ym","적용 시작"),Map.entry("scheduledChanges","예정된 변경"),
        Map.entry("peopleCount","소속 인력"),Map.entry("projectCount","진행 프로젝트"),Map.entry("use_yn","사용 여부"),Map.entry("sort_seq","순서"));
    private static final Map<String,String> SORTS=Map.ofEntries(
        Map.entry("bsn_part_nm","bsn_part_nm"),Map.entry("currentStandard.ldr_nm","ldr_nm"),
        Map.entry("currentStandard.prft_dstrb_rt","prft_dstrb_rt"),Map.entry("currentStandard.bsn_fee_amt","bsn_fee_amt"),
        Map.entry("currentStandard.aply_bgn_ym","aply_bgn_ym"),Map.entry("peopleCount","\"peopleCount\""),
        Map.entry("projectCount","\"projectCount\""),Map.entry("use_yn","use_yn"),Map.entry("sort_seq","sort_seq"));
    private final SalesPartManagementMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    private final ObjectMapper json;
    public SalesPartExcelService(SalesPartManagementMapper mapper, CurrentUser user, PermissionService permissions, Clock clock, ObjectMapper json) {
        this.mapper=mapper;this.user=user;this.permissions=permissions;this.clock=clock;this.json=json;
    }
    static List<String> columns(List<String> requested) {
        if(requested==null || requested.isEmpty()) throw columnsError();
        List<String> result=new ArrayList<>();
        for(String key:requested) {
            if("currentStandard".equals(key)) result.addAll(List.of("ldr_nm","prft_dstrb_rt","bsn_fee_amt","aply_bgn_ym"));
            else result.add(key.startsWith("currentStandard.")?key.substring("currentStandard.".length()):key);
        }
        if(new HashSet<>(result).size()!=result.size() || result.stream().anyMatch(k->!TITLES.containsKey(k))) throw columnsError();
        return List.copyOf(result);
    }
    private static ApiException columnsError() { return ApiException.badRequest("내려받을 열을 확인하세요.","columns"); }
    static String order(String sort) {
        if(sort==null || sort.isBlank()) return "sort_seq ASC,bsn_part_id ASC";
        String[] parts=sort.split(",",-1);
        if(parts.length!=2 || !SORTS.containsKey(parts[0]) || !Set.of("asc","desc").contains(parts[1])) throw ApiException.badRequest("정렬 항목을 확인하세요.","sort");
        return SORTS.get(parts[0])+" "+parts[1].toUpperCase(Locale.ROOT)+",bsn_part_id ASC";
    }
    @Transactional
    public Download export(List<String> requested, List<String> selected, String sort) {
        permissions.can("UI-15.download");List<String> columns=columns(requested);String order=order(sort);
        List<Long> ids=null;
        if(selected!=null) {
            if(selected.isEmpty()) throw ApiException.badRequest("선택한 파트를 확인하세요.","ids");
            ids=selected.stream().map(SalesPartInput::id).distinct().toList();
            if(mapper.existingIds(ids)!=ids.size()) throw ApiException.notFound("영업 파트가 없습니다.");
        }
        LocalDateTime now=LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        String month=YearMonth.from(now).toString();
        List<ExportRow> rows=mapper.exportRows(new ExportQuery(now.toLocalDate(),month,ids,order));
        String filename="영업파트_"+now.format(DateTimeFormatter.BASIC_ISO_DATE)+".xlsx";
        byte[] bytes=workbook(columns,rows,ids==null?"전체":"선택 "+ids.size()+"건",now);
        try {
            Map<String,Object> detail=new LinkedHashMap<>();detail.put("columns",columns);detail.put("ids",ids);detail.put("sort",order);detail.put("rowCount",rows.size());detail.put("referenceMonth",month);
            mapper.appendAccess(new Access(filename,json.writeValueAsString(detail),user.userId()));
        } catch(JsonProcessingException ex) { throw new IllegalStateException("다운로드 기록을 만들 수 없습니다.",ex); }
        return new Download(filename,bytes);
    }
    private byte[] workbook(List<String> columns,List<ExportRow> rows,String condition,LocalDateTime now) {
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            Sheet sheet=book.createSheet("영업파트");
            sheet.createRow(0).createCell(0).setCellValue("조회 조건: "+condition);
            sheet.createRow(1).createCell(0).setCellValue("내려받은 사람: "+user.name()+" · "+now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            Font font=book.createFont();font.setBold(true);
            CellStyle heading=book.createCellStyle();heading.setFont(font);heading.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());heading.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle money=book.createCellStyle();money.setDataFormat(book.createDataFormat().getFormat("#,##0"));
            Row header=sheet.createRow(3);
            for(int c=0;c<columns.size();c++) { Cell cell=header.createCell(c);cell.setCellValue(TITLES.get(columns.get(c)));cell.setCellStyle(heading);sheet.setColumnWidth(c,22*256); }
            sheet.createFreezePane(0,4);
            int index=4;
            for(ExportRow source:rows) {
                Row row=sheet.createRow(index++);
                for(int c=0;c<columns.size();c++) {
                    String key=columns.get(c);Object value=value(source,key);Cell cell=row.createCell(c);
                    if(value instanceof Number number) {cell.setCellValue(number.doubleValue());if("bsn_fee_amt".equals(key)) cell.setCellStyle(money);}
                    else if(value!=null) cell.setCellValue(value.toString());
                }
            }
            book.write(output);return output.toByteArray();
        } catch(IOException ex) { throw new IllegalStateException("엑셀 파일을 만들 수 없습니다.",ex); }
    }
    private static Object value(ExportRow r,String key) {
        return switch(key) {
            case "bsn_part_nm"->r.bsn_part_nm();case "ldr_nm"->r.ldr_nm();case "prft_dstrb_rt"->r.prft_dstrb_rt();
            case "bsn_fee_amt"->r.bsn_fee_amt();case "aply_bgn_ym"->r.aply_bgn_ym();case "scheduledChanges"->r.scheduledChanges();
            case "peopleCount"->r.peopleCount();case "projectCount"->r.projectCount();case "use_yn"->"Y".equals(r.use_yn())?"사용":"중지";
            case "sort_seq"->r.sort_seq();default->throw columnsError();
        };
    }
}
