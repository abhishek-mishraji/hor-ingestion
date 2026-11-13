package com.handsoffretail.verifone.fetch;

import org.springframework.dao.DataAccessException;

import com.handsoffretail.verifone.parse.CpjFormatException;
import com.handsoffretail.verifone.source.ZipSourceException;
import com.handsoffretail.verifone.verify.LoadCheckFailedException;
import com.handsoffretail.verifone.verify.ZipRejectedException;
import com.handsoffretail.verifone.common.PostgresErrors;

/**
 * The FAILED message of a fetch: shown to admins and stored, so it must never carry personal data.
 * The loader's own exceptions are written without it; a database refusal is reduced to the
 * constraint and the server's message (the JDBC URL disables the row detail, which could hold values).
 */
final class FailureMessages {

    private FailureMessages() {
    }

    static String of(Throwable e) {
        if (e instanceof CpjFormatException || e instanceof ZipRejectedException || e instanceof LoadCheckFailedException
                || e instanceof ZipSourceException) {
            return e.getMessage();
        }
        if (e instanceof DataAccessException) {
            String constraint = PostgresErrors.constraint(e).map(c -> " (" + c + ")").orElse("");
            return "the database refused the load" + constraint + ": "
                    + PostgresErrors.message(e).orElse(e.getClass().getSimpleName());
        }
        return "unexpected error " + e.getClass().getSimpleName() + "; see the service log";
    }

    /** Unexpected failures are logged with their stack trace; the loader's own ones are not needed there. */
    static boolean isExpected(Throwable e) {
        return !of(e).startsWith("unexpected error");
    }
}
