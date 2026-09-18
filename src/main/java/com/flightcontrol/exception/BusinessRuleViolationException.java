package com.flightcontrol.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class BusinessRuleViolationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public BusinessRuleViolationException(Map<String, String> fieldErrors) {
        super("Flight data violates business rules");
        this.fieldErrors = Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
