package com.sicost.aiops;

import com.sicost.common.ai.*;
import com.sicost.common.api.ApiException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static com.sicost.aiops.RecommendationOptionsModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecommendationReferencesTest {
    static class Time extends Clock {
        Instant now=Instant.parse("2026-10-13T15:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now,zone); }
        @Override public Instant instant() { return now; }
    }
    final Time time=new Time();
    final RecommendationOptionsMapper mapper=mock(RecommendationOptionsMapper.class);
    RecommendationOptionsService service() {
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(mapper.sources(anyString(),nullable(String.class))).thenReturn(List.of(
            new SourceRow("PROJECT",1,7L,null,"BE",null,"2026-11",1,1,BigDecimal.ONE,null,null)));
        when(mapper.sourceSkills(any())).thenReturn(List.of()); when(mapper.developers(any())).thenReturn(List.of());
        when(mapper.codes(anyString())).thenReturn(List.of());
        return new RecommendationOptionsService(mapper,new AiAccessGuard(new MockEnvironment()),new AiOwnerSession(time),new AiSourceReads(manager),time);
    }
    @Test void ownerExpiryInvalidatesHandlesEvenWhenServletSessionSurvives() {
        var service=service(); var request=new MockHttpServletRequest();
        long old=service.get(request,Map.of()).sources().get(0).sourceId();
        time.now=time.now.plusSeconds(1800);
        assertThatThrownBy(()->service.resolveCurrentSource(request,old))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(404));
        long fresh=service.get(request,Map.of()).sources().get(0).sourceId(); assertThat(fresh).isNotEqualTo(old);
    }
    @Test void callerCannotTurnRawPhysicalRowIdIntoAHandle() {
        var service=service(); var request=new MockHttpServletRequest();
        assertThatThrownBy(()->service.resolveCurrentSource(request,1))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(404));
    }
    @Test void successfulResolutionRequeriesPhysicalIdentityAndDetectsSkillChange() {
        var service=service(); var request=new MockHttpServletRequest();
        long handle=service.get(request,Map.of()).sources().get(0).sourceId();
        assertThat(service.resolveCurrentSource(request,handle).rowId()).isEqualTo(1);
        verify(mapper,times(2)).sources("2026-10",null);
        when(mapper.sourceSkills(any())).thenReturn(List.of("JAVA"));
        assertThatThrownBy(()->service.resolveCurrentSource(request,handle))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status()).isEqualTo(409));
    }
}
