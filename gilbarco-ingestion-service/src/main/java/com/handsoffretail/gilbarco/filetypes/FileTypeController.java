package com.handsoffretail.gilbarco.filetypes;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The file types this service can fetch (one per {@link FileTypeProcessor} bean), so clients never hard-code them. */
@RestController
class FileTypeController {

    private final FileTypeRegistry fileTypes;

    FileTypeController(FileTypeRegistry fileTypes) {
        this.fileTypes = fileTypes;
    }

    /** {@code [{"fileType":"PJR","isDefault":true}]}, sorted by code. */
    @GetMapping("/api/ingestion/gilbarco/file-types")
    List<FileTypeResponse> list() {
        return fileTypes.supported().stream().map(t -> new FileTypeResponse(t, t.equals(FileType.DEFAULT))).toList();
    }

    record FileTypeResponse(String fileType, boolean isDefault) {
    }
}
