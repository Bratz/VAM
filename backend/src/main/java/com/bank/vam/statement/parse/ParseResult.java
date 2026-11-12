package com.bank.vam.statement.parse;

import java.util.List;

/**
 * What a file gave: the statements that parsed, and the errors. An error with a {@code statementIndex} (1-based position
 * in the file) concerns that statement only; one without concerns the whole file (nothing readable).
 */
public record ParseResult(String fileName, List<ParsedStatement> statements, List<ParseError> errors) {

    public record ParseError(Integer statementIndex, String statementId, String message) {
    }

    static ParseResult fileError(String fileName, String message) {
        return new ParseResult(fileName, List.of(), List.of(new ParseError(null, null, message)));
    }
}
