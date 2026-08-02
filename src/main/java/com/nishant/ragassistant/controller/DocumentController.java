package com.nishant.ragassistant.controller;

import com.nishant.ragassistant.config.KafkaTopicConfig;
import com.nishant.ragassistant.dto.UploadResponse;
import com.nishant.ragassistant.event.DocumentUploadedEvent;
import com.nishant.ragassistant.service.DocumentStatusService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final KafkaTemplate<String, DocumentUploadedEvent> kafkaTemplate;
    private final DocumentStatusService statusService;
    private final String uploadDir;

    public DocumentController(
            KafkaTemplate<String, DocumentUploadedEvent> kafkaTemplate,
            DocumentStatusService statusService,
            @Value("${app.upload-dir}") String uploadDir) {
        this.kafkaTemplate = kafkaTemplate;
        this.statusService = statusService;
        this.uploadDir = uploadDir;
    }

    /**
     * PHASE 2: async. This endpoint now does the absolute minimum synchronously -
     * save the file, generate an ID, publish an event - and returns immediately.
     * Compare this to Phase 1's version, which blocked on the entire parse+chunk+embed
     * pipeline. Try uploading a large document now vs. before Phase 2 - this endpoint
     * should feel instant regardless of document size.
     */
    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String documentId = UUID.randomUUID().toString();

        // Save the file to local disk so the consumer (running separately,
        // possibly processing this seconds or minutes later) can still read it.
        // The multipart request's InputStream would already be closed by the
        // time an async consumer got to it - this is WHY we persist to disk
        // here rather than passing the stream through Kafka.
        Path uploadDirPath = Path.of(uploadDir).toAbsolutePath();
        Files.createDirectories(uploadDirPath);
        String savedFilename = documentId + "_" + file.getOriginalFilename();
        File savedFile = uploadDirPath.resolve(savedFilename).toFile();
        file.transferTo(savedFile);

        statusService.setStatus(documentId, "PENDING");

        DocumentUploadedEvent event = new DocumentUploadedEvent(
                documentId, savedFile.getAbsolutePath(), file.getOriginalFilename());

        // Publish and return - do NOT wait for ingestion. This is the entire
        // point of Phase 2. Everything after this line used to be synchronous
        // work happening inline; now it happens in IngestionConsumer, on a
        // different thread, triggered by this message.
        kafkaTemplate.send(KafkaTopicConfig.DOCUMENT_UPLOADED_TOPIC, documentId, event);

        return ResponseEntity.accepted().body(new UploadResponse(
                documentId,
                file.getOriginalFilename(),
                0,          // chunk count isn't known yet - ingestion hasn't run
                "PENDING"
        ));
    }

    /**
     * Lets the client poll for ingestion progress since /upload no longer
     * blocks until the document is actually searchable.
     */
    @GetMapping("/{documentId}/status")
    public ResponseEntity<Map<String, String>> getStatus(@PathVariable String documentId) {
        String status = statusService.getStatus(documentId);
        return ResponseEntity.ok(Map.of("documentId", documentId, "status", status));
    }
}
