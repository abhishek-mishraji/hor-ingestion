package com.handsoffretail.gilbarco.filetypes;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.handsoffretail.gilbarco.common.GilbarcoErrorCode;

/** Every {@link FileTypeProcessor} bean, by its type code. The core engine finds a file type only through this. */
@Component
public class FileTypeRegistry {

    private final Map<String, FileTypeProcessor> processors;

    public FileTypeRegistry(List<FileTypeProcessor> processors) {
        this.processors = processors.stream().collect(Collectors.toMap(p -> p.fileType().toUpperCase(Locale.ROOT),
                Function.identity(), (a, b) -> {
                    throw new IllegalStateException("two processors for file type " + a.fileType());
                }, TreeMap::new));
    }

    /**
     * The processor of a type (normalized: case and blanks ignored, missing = PJR).
     *
     * @throws com.handsoffretail.auth.client.DomainException UNSUPPORTED_FILE_TYPE (422) for any other type
     */
    public FileTypeProcessor require(String fileType) {
        String type = FileType.normalize(fileType);
        FileTypeProcessor processor = processors.get(type);
        if (processor == null) {
            throw GilbarcoErrorCode.UNSUPPORTED_FILE_TYPE.exception("file type " + type + " is not supported; supported: "
                    + processors.keySet());
        }
        return processor;
    }

    public Set<String> supported() {
        return processors.keySet();
    }
}
