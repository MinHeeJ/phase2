package com.sicost.projectboardops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.*;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.*;
import com.sicost.projectcost.ProjectCostService;
import java.io.*;
import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.transaction.annotation.*;
import static com.sicost.projectboardops.ProjectBoardExportModels.*;

@Service
public class ProjectBoardExportService {
    private final ProjectBoardExportMapper mapper;
    private final ProjectCostService costs;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final PermissionService permissions;
    private final Clock clock;
    public ProjectBoardExportService(ProjectBoardExportMapper mapper, ProjectCostService costs,
            ExcelExportService excel, CurrentUser user, PermissionService permissions, Clock clock) {
        this.mapper=mapper; this.costs=costs; this.excel=excel;
        this.user=user; this.permissions=permissions; this.clock=clock;
    }
    static BoardExportQuery query(Map<String,String> input, LocalDate today) {
        for (String key : input.keySet()) if (!Set.of("light","type","part").contains(key))
            throw ApiException.badRequest("지원하지 않는 조회 조건입니다.",key);
        String light=choice(input.get("light"),"light",Set.of("위험","주의","정상"));
        String type=choice(input.get("type"),"type",Set.of("TK","MO","DP","IN"));
        Long part=null;
        if (input.containsKey("part")) {
            try {
                String value=input.get("part");
                if (value==null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
                part=Long.valueOf(value);
            } catch (NumberFormatException ex) { throw ApiException.badRequest("영업 파트를 확인하세요.","part"); }
        }
        return new BoardExportQuery(today,YearMonth.from(today).toString(),light,type,part);
    }
    private static String choice(String value,String field,Set<String> allowed) {
        if (value!=null && !allowed.contains(value)) throw ApiException.badRequest("조회 조건을 확인하세요.",field);
        return value;
    }
    private Map<String,BigDecimal> parameters(String month) {
        var values=new HashMap<String,BigDecimal>();
        for (var row : mapper.parameters(month)) {
            BigDecimal value=row.number();
            if (value==null && row.text()!=null) {
                try { value=new BigDecimal(row.text().trim()); }
                catch (NumberFormatException ignored) { /* Report the missing numeric key below. */ }
            }
            if (value!=null) values.put(row.key(),value);
        }
        for (String key : List.of("BUDGET_RATE","SIG_DELAY_WARN","SIG_DELAY_RISK","SIG_COST_WARN","SIG_COST_RISK","SIG_STALE_DAYS"))
            if (!values.containsKey(key)) throw ApiException.badRequest("기준월 "+month+"의 숫자 기준값이 없습니다: "+key,"month");
        return values;
    }
    private BoardExportRow calculate(BoardExportSource source,BoardExportQuery query,Map<String,BigDecimal> params) {
        BigDecimal plan=source.plan_prgs_rt();
        BigDecimal actual=Set.of("MO","DP").contains(source.cntrct_ty_cd()) ? plan
            : com.sicost.apiops.ProjectProgressCalculator.rounded(source.memoProgress()==null ? BigDecimal.ZERO : source.memoProgress());
        BigDecimal schedule=actual.subtract(plan);
        BigDecimal budget=null,costRate=null,costDifference=null;
        Long amount=null;
        if ("TK".equals(source.cntrct_ty_cd())) {
            amount=costs.calculate(source.prj_id(),YearMonth.from(query.today())).cumulativeCostAmount();
            if (source.trgt_cst_amt()!=null && source.trgt_cst_amt()>0) {
                budget=BigDecimal.valueOf(source.trgt_cst_amt());
                costRate=BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(100)).divide(budget,1,RoundingMode.HALF_UP);
                costDifference=costRate.subtract(actual);
            }
        }
        LocalDate last=source.last_memo_dt()==null ? source.prfm_bgn_dt() : source.last_memo_dt();
        Long elapsed=last==null ? null : Math.max(0,ChronoUnit.DAYS.between(last,query.today()));
        boolean risk=(source.phaseCount()>0 && schedule.compareTo(params.get("SIG_DELAY_RISK").negate())<=0)
            || (source.phaseCount()>0 && costDifference!=null && costDifference.compareTo(params.get("SIG_COST_RISK"))>=0)
            || source.highOverdueCount()>0;
        boolean warning=(source.phaseCount()>0 && schedule.compareTo(params.get("SIG_DELAY_WARN").negate())<=0)
            || (source.phaseCount()>0 && costDifference!=null && costDifference.compareTo(params.get("SIG_COST_WARN"))>=0)
            || source.overdue_issue_count()>0
            || (elapsed!=null && BigDecimal.valueOf(elapsed).compareTo(params.get("SIG_STALE_DAYS"))>0);
        return new BoardExportRow(source.prj_id(),risk?"위험":warning?"주의":"정상",source.prj_nm(),source.custName(),
            source.cntrct_ty_cd(),source.pmName(),source.currentPhase(),plan,actual,costRate,
            source.open_issue_count(),source.overdue_issue_count(),source.last_memo_dt(),amount,budget);
    }
    static List<ExportColumn<BoardExportRow>> columns() {
        return List.of(
            new ExportColumn<>("light","신호등",false,false,BoardExportRow::light),
            new ExportColumn<>("prj_nm","프로젝트",false,false,BoardExportRow::prj_nm),
            new ExportColumn<>("custName","고객사",false,false,BoardExportRow::custName),
            new ExportColumn<>("cntrct_ty_cd","계약유형",false,false,BoardExportRow::cntrct_ty_cd),
            new ExportColumn<>("pmName","PM",false,false,BoardExportRow::pmName),
            new ExportColumn<>("currentPhase","현재 단계",false,false,BoardExportRow::currentPhase),
            new ExportColumn<>("plan_prgs_rt","계획 진척률",false,true,BoardExportRow::plan_prgs_rt),
            new ExportColumn<>("actl_prgs_rt","실적 진척률",false,true,BoardExportRow::actl_prgs_rt),
            new ExportColumn<>("cost_use_rt","원가 사용률",false,true,BoardExportRow::cost_use_rt),
            new ExportColumn<>("open_issue_count","열린 이슈",false,true,BoardExportRow::open_issue_count),
            new ExportColumn<>("overdue_issue_count","기한 지난 이슈",false,true,BoardExportRow::overdue_issue_count),
            new ExportColumn<>("last_memo_dt","마지막 업데이트",false,false,BoardExportRow::last_memo_dt),
            new ExportColumn<>("cumulative_cost_amt","누적 원가",true,true,BoardExportRow::cumulative_cost_amt),
            new ExportColumn<>("budget_amt","예산",true,true,BoardExportRow::budget_amt));
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public DownloadArtifact export(Map<String,String> input) {
        permissions.can(ProjectActions.key("getProjectBoardExport"));
        LocalDateTime now=LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var query=query(input,now.toLocalDate());
        var params=parameters(query.month());
        var rows=mapper.sources(query).stream().map(s->calculate(s,query,params))
            .filter(r->query.light()==null || query.light().equals(r.light()))
            .sorted(Comparator.comparingInt((BoardExportRow r)->List.of("위험","주의","정상").indexOf(r.light()))
                .thenComparing(BoardExportRow::prj_nm).thenComparingLong(BoardExportRow::prj_id)).toList();
        var conditions=new LinkedHashMap<String,Object>();
        if (query.light()!=null) conditions.put("light",query.light());
        if (query.type()!=null) conditions.put("type",query.type());
        if (query.part()!=null) conditions.put("part",query.part());
        var columns=columns();
        byte[] bytes=ratioFormatting(excel.create("신호등="+Objects.toString(query.light(),"전체")+", 계약유형="+Objects.toString(query.type(),"전체")
            +", 영업 파트="+Objects.toString(query.part(),"전체"),user.name(),now,columns,rows));
        String filename="프로젝트현황판_"+query.today().format(DateTimeFormatter.BASIC_ISO_DATE)+".xlsx";
        var metadata=new LinkedHashMap<String,Object>();
        metadata.put("filename",filename); metadata.put("conditions",conditions);
        metadata.put("columns",columns.stream().map(c->Map.of("key",c.key(),"sensitive",c.sensitive())).toList());
        metadata.put("rowCount",rows.size());
        try { mapper.access(new BoardExportAccess(filename,new ObjectMapper().writeValueAsString(metadata),user.userId())); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("다운로드 기록을 만들 수 없습니다.",ex); }
        return DownloadArtifact.workbook(filename,bytes);
    }
    private byte[] ratioFormatting(byte[] bytes) {
        try (var workbook=new XSSFWorkbook(new ByteArrayInputStream(bytes)); var output=new ByteArrayOutputStream()) {
            var style=workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("0.0"));
            var sheet=workbook.getSheetAt(0);
            for (int row=4; row<=sheet.getLastRowNum(); row++)
                for (int col=6; col<=8; col++) sheet.getRow(row).getCell(col).setCellStyle(style);
            workbook.write(output); return output.toByteArray();
        } catch (IOException ex) { throw new IllegalStateException("엑셀 파일 생성에 실패했습니다.",ex); }
    }
}
