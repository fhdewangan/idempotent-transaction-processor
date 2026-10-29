
package com.example.transactions.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.example.transactions.model.TransactionRequest;
import tools.jackson.databind.ObjectMapper;

@Component
public class ClasspathFileTransactionSource implements TransactionSource {

    private final ObjectMapper objectMapper;

    public ClasspathFileTransactionSource(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "classpath:sample-batch.json";
    }

    @Override
    public List<TransactionRequest> fetch() {
        ClassPathResource resource =
                new ClassPathResource("sample-batch.json");

        try (InputStream input = resource.getInputStream()) {
            TransactionRequest[] requests =
                    objectMapper.readValue(input, TransactionRequest[].class);
            return Arrays.asList(requests);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to read sample-batch.json", ex);
        }
    }
}
