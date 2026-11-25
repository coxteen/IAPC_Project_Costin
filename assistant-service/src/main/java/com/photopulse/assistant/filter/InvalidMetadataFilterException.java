package com.photopulse.assistant.filter;

import lombok.Getter;

import java.util.List;

@Getter
public class InvalidMetadataFilterException extends RuntimeException {

    private final String invalidToken;
    private final List<String> allowedFields;
    private final List<String> allowedOperators;

    public InvalidMetadataFilterException(String message,
                                          String invalidToken,
                                          List<String> allowedFields,
                                          List<String> allowedOperators) {
        super(message);
        this.invalidToken = invalidToken;
        this.allowedFields = allowedFields;
        this.allowedOperators = allowedOperators;
    }
}