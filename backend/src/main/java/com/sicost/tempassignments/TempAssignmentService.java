package com.sicost.tempassignments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.tempassignments.TempAssignmentModels.*;

@Service
@Transactional(readOnly = true)
public class TempAssignmentService {
    private final TempAssignmentMapper mapper;
    private final CurrentUser user;
    private final ObjectMapper json;
    public TempAssignmentService(TempAssignmentMapper mapper, CurrentUser user, ObjectMapper json) {
        this.mapper=mapper; this.user=user; this.json=json;
    }
    public TempAssignmentPage list(Map<String,String> parameters) {
        var q=TempAssignmentInput.query(parameters);
        return new TempAssignmentPage(mapper.list(q).stream().map(this::item).toList(),q.page(),q.size(),mapper.count(q));
    }
    private TempAssignment item(Row r) {
        return new TempAssignment(r.need_id(),r.dev_id(),r.bgn_ym(),r.end_ym(),r.mth_mm_qty(),
            r.tmpr_asgn_src_cd(),r.mtch_scr(),r.id(),r.status(),r.asgn_id(),mapper.warnings(r));
    }
    private Row require(long id) {
        var row=mapper.row(TempAssignmentInput.id(id));
        if(row==null) throw ApiException.notFound("가배정이 없습니다."); return row;
    }
    private Need lock(long needId) {
        Long parent=mapper.opportunityId(needId);
        if(parent==null) throw TempAssignmentInput.error("need_id");
        String status=mapper.lockOpportunity(parent);
        Need need=mapper.lockNeed(needId);
        if(need==null || need.oppt_id()!=parent) throw TempAssignmentInput.error("need_id");
        return need;
    }
    private void open(Need need) {
        if(!"OPEN".equals(mapper.lockOpportunity(need.oppt_id())) || !"Y".equals(need.use_yn()))
            throw ApiException.conflict("OPPORTUNITY_CLOSED","진행 중인 필요 인력에만 가배정할 수 있습니다.","need_id");
    }
    @Transactional
    public TempAssignment create(Write input) {
        // The same validation applies to non-HTTP callers.
        input=TempAssignmentInput.parse(json.valueToTree(input));
        Need need=lock(input.need_id()); open(need);
        if(mapper.lockDeveloper(input.dev_id())==null) throw TempAssignmentInput.error("dev_id");
        if(mapper.duplicate(input)) throw ApiException.conflict("DUPLICATE_TEMP_ASSIGNMENT",
            "같은 필요 인력·개발자의 겹치는 월에 가배정이 있습니다.","dev_id");
        var score="NEXT".equals(input.tmpr_asgn_src_cd()) ? mapper.recommendationScore(input) : null;
        if("NEXT".equals(input.tmpr_asgn_src_cd()) && score==null) throw TempAssignmentInput.error("tmpr_asgn_src_cd");
        long id=mapper.insert(new Insert(input,score,user.userId()));
        Row row=require(id);
        mapper.appendHistory(new History(Long.toString(id),"INSERT",null,snapshot(row),user.userId()));
        return item(row);
    }
    @Transactional
    public TempAssignment release(long id) {
        Row initial=require(id);
        Need need=lock(initial.need_id());
        Row before=require(id);
        if(!"ACTIVE".equals(before.status())) throw processed();
        open(need);
        if(mapper.release(new Release(id,user.userId()))!=1) throw processed();
        Row after=require(id);
        mapper.appendHistory(new History(Long.toString(id),"UPDATE",snapshot(before),snapshot(after),user.userId()));
        return item(after);
    }
    private ApiException processed() {
        return ApiException.conflict("TEMP_ASSIGNMENT_NOT_ACTIVE","이미 처리된 가배정입니다.","id");
    }
    private String snapshot(Row row) {
        try { return json.writeValueAsString(row); }
        catch(JsonProcessingException e) { throw new IllegalStateException("가배정 이력을 기록할 수 없습니다.",e); }
    }
}
