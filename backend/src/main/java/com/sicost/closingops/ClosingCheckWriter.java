package com.sicost.closingops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.sicost.closingops.ClosingModels.*;

@Service
public class ClosingCheckWriter {
    private final ClosingMapper mapper;
    private final ObjectMapper json;
    public ClosingCheckWriter(ClosingMapper mapper,ObjectMapper json) { this.mapper=mapper;this.json=json; }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public void ensureMonth(String month) { mapper.lockMonths();mapper.ensureMonth(month); }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public void save(CheckRun run) { mapper.saveChecks(run);mapper.archiveChecks(run); }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
}
