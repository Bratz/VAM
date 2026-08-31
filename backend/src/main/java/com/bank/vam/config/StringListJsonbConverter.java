package com.bank.vam.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA Converter for PostgreSQL JSONB columns that store String arrays.
 * 
 * Converts between Java List<String> and PostgreSQL JSONB format.
 * 
 * Usage in Entity:
 * <pre>
 * &#64;Convert(converter = StringListJsonbConverter.class)
 * &#64;Column(name = "operating_currencies", columnDefinition = "jsonb")
 * private List<String> operatingCurrencies;
 * </pre>
 * 
 * Database storage: ["EUR", "USD", "GBP"]
 * Java representation: List.of("EUR", "USD", "GBP")
 */
@Slf4j
@Converter(autoApply = false)
public class StringListJsonbConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("Error converting List<String> to JSON: {}", e.getMessage());
            return "[]";
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(dbData, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.error("Error converting JSON to List<String>: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}