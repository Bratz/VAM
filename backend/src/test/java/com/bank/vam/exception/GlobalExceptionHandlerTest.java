package com.bank.vam.exception;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A request the caller got wrong is a 4xx saying what is wrong, not a 500 "unexpected error". */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @SuppressWarnings("unused")
    private void endpoint(UUID corporateId) {
    }

    private MethodParameter corporateIdParam() throws NoSuchMethodException {
        return new MethodParameter(getClass().getDeclaredMethod("endpoint", UUID.class), 0);
    }

    @Test
    void aMissingRequiredHeaderIsA400NamingTheHeader() throws Exception {
        // GET /forecasts/latest without X-Corporate-Id used to answer 500 "An unexpected error occurred"
        var response = handler.handleGenericException(new MissingRequestHeaderException("X-Corporate-Id", corporateIdParam()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).contains("X-Corporate-Id");
    }

    @Test
    void aMissingParameterOrAMalformedIdIsA400() throws Exception {
        assertThat(handler.handleGenericException(new MissingServletRequestParameterException("corporateId", "UUID"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var mismatch = new MethodArgumentTypeMismatchException("not-a-uuid", UUID.class, "corporateId", corporateIdParam(), null);
        var response = handler.handleGenericException(mismatch);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).isEqualTo("Invalid value for 'corporateId': not-a-uuid");
    }

    @Test
    void aGenuineFaultIsStillA500() {
        var response = handler.handleGenericException(new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getMessage()).isEqualTo("An unexpected error occurred");
    }
}
