package com.sicost.adjustments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import com.sicost.common.validation.ClosingValues;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.adjustments.AdjustmentModels.*;

@Service
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class AdjustmentService {
    private final AdjustmentMapper mapper;
    private final CurrentUser user;
    private final Clock clock;
    private final ObjectMapper json;
    public AdjustmentService(AdjustmentMapper mapper, CurrentUser user, Clock clock, ObjectMapper json) {
        this.mapper=mapper; this.user=user; this.clock=clock; this.json=json;
    }
    public Page list(Query query) {
        return new Page(mapper.list(query),query.page(),query.size(),mapper.count(query));
    }
    private void requireOpen(String month) {
        if ("CLOSED".equals(mapper.monthStatus(month))) {
            throw ApiException.conflict("CLOSED_MONTH","마감 월은 변경할 수 없습니다.","applyMonth");
        }
        ClosingValues.applyMonth(month,clock);
    }
    private LocalDateTime now() { return LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Row create(Input input) {
        YearMonth original=ClosingValues.month(input.originalMonth(),"originalMonth");
        ClosingValues.month(input.applyMonth(),"applyMonth");
        if (!original.isBefore(YearMonth.parse(input.applyMonth()))) throw AdjustmentInput.invalid("originalMonth");
        String reason=AdjustmentInput.reason(input.reason());
        if (!Set.of("REVENUE","COST","SGA","ETC").contains(input.type()) || input.amount()==0) throw AdjustmentInput.invalid("amount");
        mapper.lockMonths();
        requireOpen(input.applyMonth());
        if (!"CLOSED".equals(mapper.monthStatus(input.originalMonth()))) {
            throw ApiException.conflict("ORIGINAL_MONTH_NOT_CLOSED","원월은 현재 월보다 앞선 마감 월이어야 합니다.","originalMonth");
        }
        if (input.projectId()!=null && (input.projectId()<=0 || !mapper.project(input.projectId()))) throw AdjustmentInput.invalid("projectId");
        if (input.developerId()!=null && (input.developerId()<=0 || !mapper.developer(input.developerId()))) throw AdjustmentInput.invalid("developerId");
        if (input.originalAdjustmentId()!=null && (input.originalAdjustmentId()<=0 || mapper.row(input.originalAdjustmentId())==null)) {
            throw AdjustmentInput.invalid("originalAdjustmentId");
        }
        Input normalized=new Input(input.originalMonth(),input.applyMonth(),input.type(),input.amount(),reason,
            input.projectId(),input.developerId(),input.originalAdjustmentId());
        long id=mapper.insert(new Write(normalized,user.userId(),now()));
        Row row=mapper.row(id);
        if (row==null) throw new IllegalStateException("조정 저장 결과를 확인할 수 없습니다.");
        audit(new Audit(id,null,snapshot(Map.of("row",row,"input",normalized)),reason,user.userId(),"INSERT"));
        return row;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Row cancel(long id, String requestedReason) {
        if (id<=0) throw AdjustmentInput.invalid("id");
        String reason=AdjustmentInput.reason(requestedReason);
        mapper.lockMonths();
        Row before=mapper.lockRow(id);
        if (before==null) throw ApiException.notFound("전월 조정이 없습니다.");
        if ("CANCELLED".equals(before.status())) throw ApiException.conflict("ALREADY_CANCELLED","이미 취소된 조정입니다.",null);
        requireOpen(before.applyMonth());
        Cancel cancel=new Cancel(id,reason,user.userId(),now());
        if (mapper.cancel(cancel)!=1) throw ApiException.conflict("ALREADY_CANCELLED","이미 취소된 조정입니다.",null);
        Row after=mapper.row(id);
        if (after==null) throw new IllegalStateException("조정 취소 결과를 확인할 수 없습니다.");
        var detail=new LinkedHashMap<String,Object>();
        detail.put("row",after); detail.put("cancellation",cancel);
        audit(new Audit(id,snapshot(before),snapshot(detail),reason,user.userId(),"UPDATE"));
        return after;
    }
    private void audit(Audit audit) { mapper.audit(audit); mapper.access(audit); }
    private String snapshot(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
}
