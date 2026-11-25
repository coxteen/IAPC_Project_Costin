package com.photopulse.assistant.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class MetadataFilterExceptionHandler {

    public record FilterErrorResponse(
            String timestamp,
            int status,
            String error,
            String message,
            String invalidToken,
            List<String> validFields,
            List<String> validOperators
    ) {}

    @ExceptionHandler(InvalidMetadataFilterException.class)
    public ResponseEntity<FilterErrorResponse> handleInvalidFilter(InvalidMetadataFilterException ex) {
        log.warn("Rejected invalid metadata filter [token='{}']: {}", ex.getInvalidToken(), ex.getMessage());

        FilterErrorResponse body = new FilterErrorResponse(
                Instant.now().toString(),
                HttpStatus.BAD_REQUEST.value(),
                "Invalid Metadata Filter",
                ex.getMessage(),
                ex.getInvalidToken(),
                ex.getAllowedFields(),
                ex.getAllowedOperators()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}