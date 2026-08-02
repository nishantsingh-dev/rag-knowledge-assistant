package com.nishant.ragassistant.dto;

public record UploadResponse(
        String documentId,
        String filename,
        int chunkCount,
        String status   // "READY" in Phase 1 (synchronous). Will become PENDING/PROCESSING/READY/FAILED once Kafka is added.
) {}
