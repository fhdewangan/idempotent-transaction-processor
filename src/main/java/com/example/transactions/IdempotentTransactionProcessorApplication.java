package com.example.transactions;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.example.transactions.config.ProcessorProperties;
import com.example.transactions.ingest.ClasspathFileTransactionSource;
import com.example.transactions.service.BatchProcessingService;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ProcessorProperties.class)
public class IdempotentTransactionProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(
                IdempotentTransactionProcessorApplication.class, args);
    }

    @Bean
    ApplicationRunner sampleIngestRunner(
            ProcessorProperties properties,
            BatchProcessingService batches,
            ClasspathFileTransactionSource sampleSource) {
        return args -> {
            if (properties.ingestSampleOnStartup()) {
                batches.process(sampleSource);
            }
        };
    }
}
