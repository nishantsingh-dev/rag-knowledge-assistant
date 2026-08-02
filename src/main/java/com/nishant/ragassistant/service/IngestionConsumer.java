package com.nishant.ragassistant.service;

import com.nishant.ragassistant.config.KafkaTopicConfig;
import com.nishant.ragassistant.event.DocumentUploadedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * This is what Phase 2 actually buys you: the upload HTTP request returns
 * immediately (see DocumentController), and THIS class does the slow work
 * - parsing, chunking, embedding - on its own thread, triggered by a Kafka
 * message instead of a waiting HTTP client.
 */
@Component
public class IngestionConsumer {

    private static final Logger log = LoggerFactory.getLogger(IngestionConsumer.class);

    private final IngestionService ingestionService;
    private final DocumentStatusService statusService;

    public IngestionConsumer(IngestionService ingestionService, DocumentStatusService statusService) {
        this.ingestionService = ingestionService;
        this.statusService = statusService;
    }

    @KafkaListener(topics = KafkaTopicConfig.DOCUMENT_UPLOADED_TOPIC, groupId = "rag-ingestion-group")
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        log.info("Received DocumentUploadedEvent for documentId={}, file={}",
                event.documentId(), event.originalFilename());

        statusService.setStatus(event.documentId(), "PROCESSING");

        try {
            FileSystemResource resource = new FileSystemResource(event.filePath());
            IngestionService.IngestResult result = ingestionService.ingest(
                    resource, event.originalFilename(), event.documentId());

            statusService.setStatus(event.documentId(), "READY");
            log.info("Ingestion complete for documentId={}, {} chunks created",
                    event.documentId(), result.chunkCount());

        } catch (Exception e) {
            // HONEST GAP: this catches the failure and marks the document FAILED so it
            // doesn't sit as PROCESSING forever, but it does NOT retry and does NOT send
            // the message to a dead-letter topic. In a real production system you'd want
            // both - Spring Kafka supports this via a DefaultErrorHandler with a
            // DeadLetterPublishingRecoverer. Left out here deliberately to keep Phase 2
            // focused on the core async mechanics; this is a good, honest "what I'd add
            // next" answer if asked about it.
            statusService.setStatus(event.documentId(), "FAILED");
            log.error("Ingestion FAILED for documentId={}: {}", event.documentId(), e.getMessage(), e);
        }
    }
}
