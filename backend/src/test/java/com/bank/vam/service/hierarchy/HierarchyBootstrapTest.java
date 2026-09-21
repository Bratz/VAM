package com.bank.vam.service.hierarchy;

import com.bank.vam.dto.hierarchy.HierarchyDto.InitializationResponse;
import com.bank.vam.entity.Program;
import com.bank.vam.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * Every program bootstraps a hierarchy, and accounts are created under a parent
 * node, so a program whose bootstrap failed cannot hold any account. The old
 * create path swallowed that failure; bootstrap() must not.
 */
class HierarchyBootstrapTest {

    private Program program() {
        Program p = new Program();
        p.setId(UUID.randomUUID());
        p.setProgramCode("P1");
        p.setProgramName("Program One");
        p.setCurrencyCode("AED");
        return p;
    }

    private HierarchyService withInitResult(Program p, String status, boolean success) {
        HierarchyService service = spy(new HierarchyService(null, null, null, null));
        doReturn(InitializationResponse.builder().success(success).status(status).message("boom").build())
            .when(service).initializeHierarchyWithResponse(eq(p.getId()), any());
        return service;
    }

    @Test
    void failedBootstrapThrowsSoTheCreateRollsBack() {
        Program p = program();
        assertThatThrownBy(() -> withInitResult(p, "FAILED", false).bootstrap(p))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("P1");
    }

    @Test
    void alreadyInitializedIsNotAFailure() {
        Program p = program();
        assertThatCode(() -> withInitResult(p, "ALREADY_INITIALIZED", false).bootstrap(p))
            .doesNotThrowAnyException();
    }
}
