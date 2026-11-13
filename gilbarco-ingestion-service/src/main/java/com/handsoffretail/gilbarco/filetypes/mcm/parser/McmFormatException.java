package com.handsoffretail.gilbarco.filetypes.mcm.parser;

import com.handsoffretail.gilbarco.filetypes.FileFormatException;

/** An MCM file or package the rules do not explain; the whole day is refused. */
public class McmFormatException extends FileFormatException {

    public McmFormatException(String message) {
        super(message);
    }

    public McmFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
