package com.sicost.cashflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.billingsops.BillingRevenueQuery;
import com.sicost.common.api.ApiException;
import com.sicost.common.excel.ExcelExportService;
import com.sicost.common.excel.ExcelExportService.ExportColumn;
import com.sicost.common.security.*;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.cashflow.CashFlowModels.*;

@Service
@Transactional(readOnly=true)
public class CashFlowService {
    private final CashFlowMapper mapper;
    private final BillingRevenueQuery billing;
    private final Clock clock;
    private final ExcelExportService excel;
    private final CurrentUser user;
    private final PermissionService permissions;
    @org.springframework.beans.factory.annotation.Autowired private ObjectMapper json;
    public CashFlowService(CashFlowMapper mapper,BillingRevenueQuery billing,Clock clock,
            ExcelExportService excel,CurrentUser user,PermissionService permissions) {
        this.mapper=mapper;this.billing=billing;this.clock=clock;this.excel=excel;this.user=user;this.permissions=permissions;
    }
    private LocalDate today(){return clock.instant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();}
    private void open(String month){if("CLOSED".equals(mapper.monthStatus(month)))throw ApiException.conflict("CLOSED_MONTH","마감된 달은 변경할 수 없습니다.","month");}
    private void dates(Inflow row){open(row.month());if(row.planned_date()!=null)open(YearMonth.from(row.planned_date()).toString());if(row.actual_date()!=null)open(YearMonth.from(row.actual_date()).toString());}
    private String snapshot(Object value){try{return value==null?null:json.writeValueAsString(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}}
    public Opening opening(String month){permissions.can("UI-21.read");CashFlowInput.month(month);var initial=mapper.initial();if(initial==null)throw ApiException.notFound("기초 잔액이 없습니다.");if(initial.month().equals(month))return new Opening(month,initial.amount());var flow=flow(CashFlowInput.month(month).getYear(),"BOTH");return new Opening(month,flow.months().get(CashFlowInput.month(month).getMonthValue()-1).actual_opening());}
    @Transactional public Opening saveOpening(Opening input){
        permissions.can("UI-21.update");open(input.month());mapper.lockOpening();
        if(mapper.initial()!=null)throw ApiException.conflict("OPENING_BALANCE_REQUIRED","시작 잔액은 최초 한 번만 입력할 수 있습니다.","amount");
        mapper.saveOpening(new OpeningWrite(input,user.userId()));
        mapper.audit(new Audit("fin.cash_bal",input.month(),"INSERT",null,snapshot(input),user.userId()));return input;
    }
    public Inflows inflows(){permissions.can("UI-21.read");var rows=mapper.inflows();String month=YearMonth.from(today()).toString();long amount=0;for(var row:rows)if(month.equals(row.month()))amount=Math.addExact(amount,row.amount());return new Inflows(month,amount,rows,rows.size());}
    @Transactional public Inflow saveInflow(Inflow input,boolean update){
        permissions.can(update?"UI-21.update":"UI-21.create");
        if(input.actual_date()!=null&&input.actual_date().isAfter(today()))throw CashFlowInput.invalid("actual_date");
        Inflow before=null;if(update){before=mapper.inflow(input.inflw_id());if(before==null)throw ApiException.notFound("기타 수입이 없습니다.");dates(before);}
        dates(input);long id;
        if(update){id=input.inflw_id();if(mapper.updateInflow(new InflowWrite(input,user.userId()))!=1)throw ApiException.notFound("기타 수입이 없습니다.");}
        else id=mapper.insertInflow(new InflowWrite(input,user.userId()));
        var after=new Inflow(id,input.month(),input.amount(),input.inflw_se_cd(),input.inflw_item_nm(),input.planned_date(),input.actual_date(),input.remark());
        mapper.audit(new Audit("fin.etc_inflw",Long.toString(id),update?"UPDATE":"INSERT",snapshot(before),snapshot(after),user.userId()));return after;
    }
    private long add(long a,long b){return Math.addExact(a,b);}
    private Long closing(Long opening,long in,long out){return opening==null?null:Math.subtractExact(add(opening,in),out);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Flow flow(int year,String mode){
        permissions.can("UI-21.read");if(year<1||year>9999)throw CashFlowInput.invalid("year");
        if(!Set.of("PLAN","ACTUAL","BOTH").contains(mode))throw CashFlowInput.invalid("mode");
        var initial=mapper.initial();if(initial==null||initial.amount()==null)throw ApiException.conflict("OPENING_BALANCE_REQUIRED","먼저 시작 월 기초 잔액을 입력하세요.","amount");
        YearMonth start=YearMonth.parse(initial.month()),end=YearMonth.of(year,12),current=YearMonth.from(today());
        List<Event> events=new ArrayList<>(mapper.events());
        for(int y=start.getYear();y<=Math.min(year,current.getYear());y++)for(var cash:billing.cash(y))events.add(new Event(null,0,cash.rcpt_dt(),cash.rcpt_amt(),0,true,"BILLING"));
        Map<String,Balance> balances=new HashMap<>();for(var b:mapper.balances())balances.put(b.month(),b);
        List<Month> result=new ArrayList<>();Long plan=initial.amount(),actual=initial.amount(),forecast=initial.amount();String firstRisk=null;
        YearMonth cursor=start.isAfter(YearMonth.of(year,1))?YearMonth.of(year,1):start;
        while(!cursor.isAfter(end)){
            String month=cursor.toString();long pi=0,po=0,ai=0,ao=0,fi=0,fo=0;
            Map<String,long[]> categories=new TreeMap<>();
            for(var e:events){
                if(e.planned_date()!=null&&YearMonth.from(e.planned_date()).equals(cursor)){
                    var amounts=categories.computeIfAbsent(e.category(),k->new long[4]);int index=e.inflow()?0:1;amounts[index]=add(amounts[index],e.planned_amount());
                    if(e.inflow()){pi=add(pi,e.planned_amount());fi=add(fi,e.remaining_amount());}else{po=add(po,e.planned_amount());fo=add(fo,e.remaining_amount());}
                }
                if(e.actual_date()!=null&&!e.actual_date().isAfter(today())&&YearMonth.from(e.actual_date()).equals(cursor)){
                    var amounts=categories.computeIfAbsent(e.category(),k->new long[4]);int index=e.inflow()?2:3;amounts[index]=add(amounts[index],e.actual_amount());
                    if(e.inflow()){ai=add(ai,e.actual_amount());fi=add(fi,e.actual_amount());}else{ao=add(ao,e.actual_amount());fo=add(fo,e.actual_amount());}
                }
            }
            boolean before=cursor.isBefore(start),future=cursor.isAfter(current);var saved=balances.get(month);
            boolean closed=saved!=null&&"CLOSED".equals(saved.status());String warning=null;
            Long op=before?null:plan,oa=before?null:actual,of=before?null:forecast;
            Long pc=closing(op,pi,po),ac=closing(oa,ai,ao),fc=closing(of,fi,fo);
            if(closed&&!before){ac=saved.closing();fc=saved.closing();if(ac==null)warning="MISSING_CLOSED_SNAPSHOT";}
            if(before)warning="BEFORE_INITIAL_MONTH";
            Long minimum=saved!=null&&saved.minimum()!=null?saved.minimum():initial.minimum();
            if(fc!=null&&fc<0){warning="RISK";if(firstRisk==null&&!cursor.isBefore(current))firstRisk=month;}
            else if(fc!=null&&minimum!=null&&fc<minimum)warning="CAUTION";
            var detail=categories.entrySet().stream().map(e->new Category(e.getKey(),e.getValue()[0],e.getValue()[1],future?null:e.getValue()[2],future?null:e.getValue()[3])).toList();
            if(cursor.getYear()==year)result.add(new Month(month,op,pc,future?null:ac,fc,warning,future?null:oa,pi,po,future?null:ai,future?null:ao,of,fi,fo,closed,detail));
            if(!before){plan=pc;actual=ac;forecast=fc;}
            cursor=cursor.plusMonths(1);
        }
        List<ExportColumn<Month>> columns=new ArrayList<>(List.of(
            new ExportColumn<>("month","월",false,false,Month::month),
            new ExportColumn<>("planned_opening","기초 예정",true,true,Month::planned_opening),
            new ExportColumn<>("planned_closing","월말 예정 잔액",true,true,Month::planned_closing),
            new ExportColumn<>("actual_closing","월말 실적 잔액",true,true,Month::actual_closing),
            new ExportColumn<>("forecast_closing","예상 최종 잔액",true,true,Month::forecast_closing),
            new ExportColumn<>("warning","경고",false,false,Month::warning),
            new ExportColumn<>("planned_inflow","예정 수입",true,true,Month::planned_inflow),
            new ExportColumn<>("planned_outflow","예정 지출",true,true,Month::planned_outflow),
            new ExportColumn<>("actual_opening","전월이월 실적",true,true,Month::actual_opening),
            new ExportColumn<>("actual_inflow","실적 수입",true,true,Month::actual_inflow),
            new ExportColumn<>("actual_outflow","실적 지출",true,true,Month::actual_outflow),
            new ExportColumn<>("forecast_opening","전월이월 예상",true,true,Month::forecast_opening),
            new ExportColumn<>("forecast_inflow","예상 수입",true,true,Month::forecast_inflow),
            new ExportColumn<>("forecast_outflow","예상 지출",true,true,Month::forecast_outflow)));
        var categoryCodes=result.stream().flatMap(m->m.categories().stream()).map(Category::code).distinct().sorted().toList();
        for(String code:categoryCodes){
            columns.add(new ExportColumn<>(code+"_plan",code+" 예정 순입출금",true,true,m->m.categories().stream().filter(c->c.code().equals(code)).map(c->Math.subtractExact(c.planned_inflow(),c.planned_outflow())).findFirst().orElse(0L)));
            columns.add(new ExportColumn<>(code+"_actual",code+" 실적 순입출금",true,true,m->m.actual_closing()==null?null:m.categories().stream().filter(c->c.code().equals(code)).map(c->Math.subtractExact(c.actual_inflow(),c.actual_outflow())).findFirst().orElse(0L)));
        }
        var file=new CashFlowFile("cashflow-"+year+".xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",Base64.getEncoder().encodeToString(excel.create(year+" · "+mode,"기본 사용자",LocalDateTime.now(clock),columns,result)));
        return new Flow(year,mode,List.copyOf(result),file,firstRisk,initial.month());
    }
}
