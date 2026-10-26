package com.sicost.billstages;

import com.sicost.common.api.ApiException;
import com.sicost.common.security.CurrentUser;
import com.sicost.common.security.PermissionService;
import com.sicost.common.security.ProjectActions;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.billstages.ProjectBillStageModels.*;

@Service
@Transactional(readOnly = true)
public class ProjectBillStageService {
    private final ProjectBillStageMapper mapper;
    private final CurrentUser user;
    private final PermissionService permissions;

    public ProjectBillStageService(ProjectBillStageMapper mapper, CurrentUser user, PermissionService permissions) {
        this.mapper = mapper;
        this.user = user;
        this.permissions = permissions;
    }

    private ProjectState require(long id, boolean writing) {
        if (id < 1) throw ApiException.badRequest("프로젝트 식별자를 확인하세요.", "id");
        var project = writing ? mapper.lockProject(id) : mapper.project(id);
        if (project == null) throw ApiException.notFound("프로젝트가 없습니다.");
        if (!"TK".equals(project.cntrct_ty_cd())) {
            throw ApiException.badRequest("턴키 프로젝트만 청구 단계를 사용할 수 있습니다.", "cntrct_ty_cd");
        }
        if (writing && !"Y".equals(project.use_yn())) {
            throw ApiException.badRequest("사용 안 함 프로젝트는 수정할 수 없습니다.", "use_yn");
        }
        return project;
    }

    public Stages list(long id) {
        permissions.can(ProjectActions.key("listProjectBillStages"));
        var project = require(id, false);
        var rows = mapper.stages(id);
        return new Stages(rows, project.cur_cntrct_amt(), total(rows));
    }

    private static long add(long sum, long amount) {
        try {
            return Math.addExact(sum, amount);
        } catch (ArithmeticException e) {
            throw ApiException.badRequest("공급가액 합계의 금액 범위를 확인하세요.", "items");
        }
    }

    private static long total(List<Stage> rows) {
        long sum = 0;
        for (var row : rows) sum = add(sum, row.sply_amt());
        return sum;
    }

    private static String summary(int count, long sum) {
        return String.format(Locale.KOREA, "청구 단계 %d건 · 합계 %,d원", count, sum);
    }

    @Transactional
    public Stages save(long id, List<StageInput> inputs) {
        permissions.can(ProjectActions.key("saveProjectBillStages"));
        var project = require(id, true);
        if (inputs == null || inputs.size() > 20) {
            throw ApiException.badRequest("청구 단계는 최대 20건입니다.", "items");
        }
        var before = mapper.stages(id);
        Map<Long, Stage> existing = new HashMap<>();
        Set<Integer> occupied = new HashSet<>();
        for (var row : before) {
            existing.put(row.bill_stg_id(), row);
            occupied.add(row.stg_odr_no());
        }
        Map<Long, Integer> retained = new HashMap<>();
        long sum = 0;
        for (int index = 0; index < inputs.size(); index++) {
            var input = inputs.get(index);
            String prefix = "items[" + index + "].";
            if (input == null || input.bill_stg_nm() == null || input.bill_stg_nm().isBlank()
                    || input.bill_stg_nm().length() > 100) {
                throw ApiException.badRequest("단계명은 필수이며 100자 이하로 입력하세요.", prefix + "bill_stg_nm");
            }
            if (input.sply_amt() < 1) throw ApiException.badRequest("공급가액은 1원 이상입니다.", prefix + "sply_amt");
            if (input.bill_plan_dt() == null) throw ApiException.badRequest("청구 예정일을 입력하세요.", prefix + "bill_plan_dt");
            sum = add(sum, input.sply_amt());
            if (input.bill_stg_id() != null) {
                if (!existing.containsKey(input.bill_stg_id())) throw ApiException.notFound("해당 프로젝트의 청구 단계가 없습니다.");
                if (retained.put(input.bill_stg_id(), index) != null) {
                    throw ApiException.badRequest("청구 단계가 중복되었습니다.", prefix + "bill_stg_id");
                }
            }
        }
        for (var row : before) {
            if (!row.billed()) continue;
            Integer index = retained.get(row.bill_stg_id());
            if (index == null || index + 1 != row.stg_odr_no() || !same(row, inputs.get(index))) {
                throw ApiException.conflict("STAGE_BILLED", "청구된 단계는 수정·삭제하거나 차수를 바꿀 수 없습니다.", "items");
            }
        }
        int parking = -1;
        for (var row : before) {
            if (row.billed()) continue;
            if (!retained.containsKey(row.bill_stg_id())) {
                mapper.delete(new StageKey(id, row.bill_stg_id()));
            } else {
                // Park outside all old/new ordinals before assigning the final contiguous order.
                while (occupied.contains(parking)) parking--;
                mapper.position(new StagePosition(id, row.bill_stg_id(), parking));
                occupied.add(parking--);
            }
        }
        for (int index = 0; index < inputs.size(); index++) {
            var input = inputs.get(index);
            var write = new StageWrite(id, input, index + 1, user.userId());
            if (input.bill_stg_id() == null) mapper.insert(write);
            else if (!existing.get(input.bill_stg_id()).billed()) mapper.update(write);
        }
        mapper.history(new StageHistory(Long.toString(id), summary(before.size(), total(before)),
            summary(inputs.size(), sum), user.userId()));
        return new Stages(mapper.stages(id), project.cur_cntrct_amt(), sum);
    }

    private static boolean same(Stage row, StageInput input) {
        return row.bill_stg_nm().equals(input.bill_stg_nm()) && row.sply_amt() == input.sply_amt()
            && Objects.equals(row.bill_plan_dt(), input.bill_plan_dt());
    }
}
