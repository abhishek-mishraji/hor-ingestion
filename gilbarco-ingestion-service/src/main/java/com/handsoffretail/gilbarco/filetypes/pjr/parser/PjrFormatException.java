package com.handsoffretail.gilbarco.filetypes.pjr.parser;

import com.handsoffretail.gilbarco.filetypes.FileFormatException;

/** A PJR file the proven rules do not explain; the whole day is refused. */
public class PjrFormatException extends FileFormatException {

    public PjrFormatException(String message) {
        super(message);
    }

    public PjrFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
