package com.handsoffretail.gilbarco.common;

import java.util.Optional;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Reads what PostgreSQL said about a failed statement, so repositories can turn a constraint
 * violation into a domain exception with a clear message.
 */
public final class PostgresErrors {

    public static final String UNIQUE_VIOLATION = "23505";
    public static final String CHECK_VIOLATION = "23514";
    public static final String FOREIGN_KEY_VIOLATION = "23503";

    private PostgresErrors() {
    }

    /** The server error under {@code e}'s cause chain, if any. */
    public static Optional<ServerErrorMessage> serverError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                return Optional.of(psql.getServerErrorMessage());
            }
        }
        return Optional.empty();
    }

    public static Optional<String> constraint(Throwable e) {
        return serverError(e).map(ServerErrorMessage::getConstraint);
    }

    public static boolean isViolation(Throwable e, String sqlState) {
        return serverError(e).map(message -> sqlState.equals(message.getSQLState())).orElse(false);
    }

    /** The message a trigger raised, or the server's own message. */
    public static Optional<String> message(Throwable e) {
        return serverError(e).map(ServerErrorMessage::getMessage);
    }
}
