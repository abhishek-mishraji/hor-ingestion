package com.handsoffretail.gilbarco.filetypes;

/**
 * A file of a day package cannot be understood (unknown element, missing value, wrong business date). The
 * message names the file and the rule and never carries personal data; it becomes the FAILED fetch message.
 */
public class FileFormatException extends RuntimeException {

    public FileFormatException(String message) {
        super(message);
    }

    public FileFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
