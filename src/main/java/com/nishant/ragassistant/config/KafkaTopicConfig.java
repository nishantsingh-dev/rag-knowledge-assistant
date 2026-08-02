package com.nishant.ragassistant.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    public static final String DOCUMENT_UPLOADED_TOPIC = "document.uploaded";

    /**
     * Declaring the topic explicitly (rather than relying on Kafka's
     * auto-create-on-first-publish behavior) is a deliberate choice -
     * it means the topic's partition count is intentional, not accidental,
     * and it's visible here in code instead of hidden in broker defaults.
     */
    @Bean
    public NewTopic documentUploadedTopic() {
        return TopicBuilder.name(DOCUMENT_UPLOADED_TOPIC)
                .partitions(3)   // allows up to 3 consumer instances to process in parallel later
                .replicas(1)     // single-node dev setup - would be 3 in a real production cluster
                .build();
    }
}
