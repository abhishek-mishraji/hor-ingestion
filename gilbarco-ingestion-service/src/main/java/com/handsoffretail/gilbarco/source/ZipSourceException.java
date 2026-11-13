package com.handsoffretail.gilbarco.source;

/** The zip source could not answer (access, network, ambiguous versions). Never contains credentials. */
public class ZipSourceException extends RuntimeException {

    public ZipSourceException(String message) {
        super(message);
    }

    public ZipSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
