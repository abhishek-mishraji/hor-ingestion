package com.handsoffretail.gilbarco.verify;

/** The zip failed verification before anything was loaded; the message says which rule. */
public class ZipRejectedException extends RuntimeException {

    public ZipRejectedException(String message) {
        super(message);
    }

    public ZipRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
