package com.nishant.ragassistant.event;

/**
 * Published to Kafka when a document is uploaded. Deliberately small -
 * it carries a REFERENCE to the file (path) and the ID, not the file's
 * bytes. Keeping Kafka messages small is a real design constraint - large
 * messages hurt broker performance and aren't what Kafka is optimized for.
 */
public record DocumentUploadedEvent(
        String documentId,
        String filePath,
        String originalFilename
) {}
