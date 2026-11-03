package com.sicost.common.dashboard;

import com.fasterxml.jackson.databind.JsonNode;

/** Implemented by the owner of GET /api/dashboard; returns its data DTO, not its envelope.
 * The owner must use the supplied context for every date-aware read and preserve required nulls.
 * This port intentionally contains no financial calculation or persistence implementation.
 */
@FunctionalInterface
public interface DashboardReadSource {
    JsonNode read(DashboardReadContext context);
}
