package com.sicost.apiops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.dashboard.DashboardReadContext;
import com.sicost.common.dashboard.DashboardReadSource;
import org.springframework.stereotype.Component;

/** HTTP and export use the same composition and the already captured request time. */
@Component
public class DashboardReadAdapter implements DashboardReadSource {
    private final DashboardQueryService query;
    private final ObjectMapper json;
    public DashboardReadAdapter(DashboardQueryService query,ObjectMapper json) {
        this.query=query;this.json=json;
    }
    @Override public JsonNode read(DashboardReadContext context) {
        return json.valueToTree(query.getDashboard(new DashboardInput.Context(
            context.asOf().toInstant(),context.today(),context.month())));
    }
}
