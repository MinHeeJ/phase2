package com.sicost.board;

import com.sicost.assignments.AssignmentMapper;
import com.sicost.assignments.AssignmentModels;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.board.WorkforceBoardModels.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class WorkforceBoardService {
    private final WorkforceBoardMapper mapper;
    private final AssignmentMapper assignments;
    private final Clock clock;
    public WorkforceBoardService(WorkforceBoardMapper mapper, AssignmentMapper assignments, Clock clock) {
        this.mapper=mapper; this.assignments=assignments; this.clock=clock;
    }
    public BoardPage getWorkforceBoard(Map<String,String> values) {
        var query=WorkforceBoardInput.parse(values,false);
        var snapshot=snapshot(query);
        return new BoardPage(snapshot.rows().stream().skip((long)query.page()*query.size())
            .limit(query.size()).toList(),query.page(),query.size(),snapshot.rows().size(),snapshot.monthly_totals());
    }
    public Snapshot snapshot(Query query) {
        var today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var sources=assignments.list(new AssignmentModels.Query(query.year(),null,null,null,null,
            0,50,"dev_nm,asc",today,YearMonth.from(today).toString(),query.start(),query.end()));
        var months=mapper.months(query);
        var leaves=mapper.leaves(query);
        var temps=mapper.temporary(query);
        var rows=new ArrayList<BoardRow>();
        for (var person:mapper.people(query)) {
            var cells=new ArrayList<Cell>();
            for (int i=1;i<=12;i++) {
                var month=YearMonth.of(query.year(),i);
                var active=sources.stream().filter(a -> a.dev_id()==person.dev_id()
                    && !a.bgn_dt().isAfter(month.atEndOfMonth()) && !a.end_dt().isBefore(month.atDay(1))).toList();
                var details=active.stream().map(a -> detail(a,month,months,sources)).toList();
                var confirmed=details.stream().map(a -> a.row().current_month_mm_qty()).reduce(BigDecimal.ZERO,BigDecimal::add);
                var overlay=temps.stream().filter(t -> t.dev_id()==person.dev_id()
                    && t.bgn_ym().compareTo(month.toString())<=0 && t.end_ym().compareTo(month.toString())>=0)
                    .map(TemporarySource::item).toList();
                var temporary=overlay.stream().map(Temporary::mth_mm_qty).reduce(BigDecimal.ZERO,BigDecimal::add);
                boolean retired=person.rsgn_dt()!=null && !person.rsgn_dt().isAfter(month.atEndOfMonth())
                    || leaves.stream().anyMatch(l -> l.dev_id()==person.dev_id() && l.work_stts_cd().equals("RESIGN")
                        && !l.bgn_dt().isAfter(month.atEndOfMonth()));
                boolean leave=leaves.stream().anyMatch(l -> l.dev_id()==person.dev_id() && !l.work_stts_cd().equals("RESIGN")
                    && !l.bgn_dt().isAfter(month.atEndOfMonth()) && (l.end_dt()==null || !l.end_dt().isBefore(month.atDay(1))));
                var withdrawal=active.stream().map(AssignmentModels.Row::end_dt)
                    .filter(d -> YearMonth.from(d).equals(month)).max(Comparator.naturalOrder()).orElse(null);
                String status=retired?"퇴사":leave?"휴직":confirmed.signum()>0?(withdrawal==null?"투입중":"철수예정")
                    :temporary.signum()>0?"가배정":"대기";
                var warnings=new ArrayList<String>();
                if(confirmed.compareTo(BigDecimal.ONE)>0) warnings.add("MM 1.0 초과");
                if(month.isBefore(YearMonth.from(today))) warnings.add("과거 직무·등급은 현재 표시값이며 마감 당시 분류 원천 미확인");
                cells.add(new Cell(month.toString(),confirmed,temporary,active.size(),status,withdrawal,
                    confirmed.min(BigDecimal.ONE).multiply(new BigDecimal("100")),details,overlay,List.copyOf(warnings)));
            }
            if(!query.free_only() || cells.stream().anyMatch(WorkforceBoardService::free))
                rows.add(new BoardRow(person.dev_id(),person.dev_nm(),person.mng_no(),person.job_cd(),person.grd_cd(),List.copyOf(cells)));
        }
        rows.sort(order(query.sort()));
        var totals=new ArrayList<MonthlyTotal>();
        for(int i=0;i<12;i++) {
            int index=i;
            var cells=rows.stream().map(r -> r.months().get(index)).toList();
            totals.add(new MonthlyTotal(YearMonth.of(query.year(),i+1).toString(),cells.stream().filter(WorkforceBoardService::free).count(),
                cells.stream().map(c -> BigDecimal.ONE.subtract(c.confirmed_mm()).max(BigDecimal.ZERO)).reduce(BigDecimal.ZERO,BigDecimal::add)));
        }
        return new Snapshot(List.copyOf(rows),List.copyOf(totals));
    }
    private static boolean free(Cell cell) {
        return cell.confirmed_mm().signum()==0 && !Set.of("퇴사","휴직").contains(cell.status());
    }
    private static AssignmentModels.Item detail(AssignmentModels.Row a, YearMonth month, List<MonthlyAssignment> months,
            List<AssignmentModels.Row> sources) {
        var mm=months.stream().filter(m -> m.asgn_id()==a.asgn_id() && m.month().equals(month.toString()))
            .map(MonthlyAssignment::mm).reduce(BigDecimal.ZERO,BigDecimal::add);
        var person=months.stream().filter(m -> m.month().equals(month.toString()))
            .filter(m -> sources.stream().anyMatch(s -> s.dev_id()==a.dev_id() && s.asgn_id()==m.asgn_id()))
            .map(MonthlyAssignment::mm).reduce(BigDecimal.ZERO,BigDecimal::add);
        var row=new AssignmentModels.Row(a.asgn_id(),a.asgn_no(),a.prj_id(),a.prj_nm(),a.dev_id(),a.dev_nm(),a.mng_no(),a.job_cd(),
            a.bgn_dt(),a.end_dt(),a.plan_end_dt(),a.asgn_rt(),a.asgn_se_cd(),a.untprc_base_cd(),a.ext_psblty_cd(),
            a.cntrct_dev_id(),a.cntrct_dev_nm(),a.asgn_stts_cd(),a.rplc_asgn_id(),a.bill_untprc_amt(),mm,person,a.year_mm_qty(),
            a.updated_at(),a.rmrk_desc(),a.extend_count(),a.over_mm(),a.pre_contract(),a.on_leave(),a.dispatch_nonreg());
        return new AssignmentModels.Item(row,mm.compareTo(BigDecimal.ONE)>0
            ?List.of(new AssignmentModels.Warning("OVER_MM","MM 1.0 초과",month.toString())):List.of());
    }
    private static Comparator<BoardRow> order(String sort) {
        var key=sort.split(",")[0];
        Comparator<BoardRow> order=key.equals("dev_id")?Comparator.comparingLong(BoardRow::dev_id)
            :Comparator.comparing(r -> switch(key) {
                case "dev_nm" -> r.dev_nm(); case "mng_no" -> r.mng_no();
                case "job_cd" -> r.job_cd(); default -> Objects.toString(r.grd_cd(),"");
            });
        return (sort.endsWith(",desc")?order.reversed():order).thenComparingLong(BoardRow::dev_id);
    }
}
