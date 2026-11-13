package com.handsoffretail.verifone.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import com.handsoffretail.auth.client.CommonErrorCode;
import com.handsoffretail.auth.client.DomainException;
import com.handsoffretail.auth.client.ErrorCode;

/**
 * The error codes of this service. Rendered as RFC 9457 problem details by auth-client's ProblemDetailsAdvice
 * ({@code code} property = the enum name). Codes are kept from the old service so clients do not change.
 */
public enum VerifoneErrorCode implements ErrorCode {

    FETCH_RUNNING(HttpStatus.CONFLICT),
    FETCH_QUEUE_FULL(HttpStatus.CONFLICT),
    STORE_INACTIVE(HttpStatus.CONFLICT),
    STORE_CODE_MISMATCH(HttpStatus.CONFLICT),
    STORE_NOT_VERIFONE(HttpStatus.UNPROCESSABLE_CONTENT),
    DATE_IN_FUTURE(HttpStatus.UNPROCESSABLE_CONTENT),
    BAD_RANGE(HttpStatus.UNPROCESSABLE_CONTENT),
    BAD_LIMIT(HttpStatus.UNPROCESSABLE_CONTENT),
    BAD_OFFSET(HttpStatus.UNPROCESSABLE_CONTENT);

    private final HttpStatusCode status;

    VerifoneErrorCode(HttpStatusCode status) {
        this.status = status;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatusCode status() {
        return status;
    }

    /** The exception for this code. */
    public DomainException exception(String detail) {
        return new DomainException(this, detail);
    }

    /** 404 for a thing that does not exist (or that the caller may not know exists). */
    public static DomainException notFound(String what, long id) {
        return new DomainException(CommonErrorCode.NOT_FOUND, what + " " + id + " not found");
    }
}
