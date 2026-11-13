package com.handsoffretail.gilbarco.source;

import java.time.Instant;

/** An object as storage lists it. */
public record StoredObject(String key, String etag, long sizeBytes, Instant lastModified) {
}
