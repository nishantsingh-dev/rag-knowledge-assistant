package com.nishant.ragassistant.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks ingestion status per document: PENDING -> PROCESSING -> READY / FAILED.
 *
 * HONEST LIMITATION (say this out loud in an interview, don't hide it):
 * this is in-memory only. If the app restarts, all status history is lost -
 * a document that's actually READY in pgvector would show as unknown here.
 * The real fix is a status column in a Postgres table, updated in the same
 * transaction as ingestion. Kept in-memory here to keep Phase 2 focused on
 * the Kafka mechanics without also introducing a new DB table in the same step.
 */
@Service
public class DocumentStatusService {

    private final Map<String, String> statusByDocumentId = new ConcurrentHashMap<>();

    public void setStatus(String documentId, String status) {
        statusByDocumentId.put(documentId, status);
    }

    public String getStatus(String documentId) {
        return statusByDocumentId.getOrDefault(documentId, "UNKNOWN");
    }
}
