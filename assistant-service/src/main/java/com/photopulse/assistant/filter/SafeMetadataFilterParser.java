package com.photopulse.assistant.filter;

import com.photopulse.assistant.filter.ValidatedMetadataFilter.Compound;
import com.photopulse.assistant.filter.ValidatedMetadataFilter.Condition;
import com.photopulse.assistant.filter.ValidatedMetadataFilter.LogicalCombinator;
import com.photopulse.assistant.filter.ValidatedMetadataFilter.Operator;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SafeMetadataFilterParser {

    public static final List<String> ALLOWED_FIELDS = List.of(
            "tenant_id",
            "camera_model",
            "brand",
            "doc_id",
            "version",
            "source_type"
    );

    public static final List<String> ALLOWED_OPERATORS = List.of(
            "eq", "ne", "in", "AND", "OR"
    );

    private static final Set<String> FIELD_SET = Set.copyOf(ALLOWED_FIELDS);
    private static final Pattern SAFE_VALUE_PATTERN = Pattern.compile("^[a-zA-Z0-9_.:/@\\- ]{1,128}$");

    private enum TokenType {
        IDENT, STRING, LPAREN, RPAREN, COMMA, AND, OR, EOF
    }

    private record Token(TokenType type, String value) {}

    /**
     * Parseaza si valideaza un parametru URL de filtrare (ex: "brand:eq:Sony AND version:eq:'1.0'").
     * Returneaza null daca filtrul este null sau gol (fallthrough pe fluxul standard).
     */
    public ValidatedMetadataFilter parse(String rawFilter) {
        if (rawFilter == null || rawFilter.isBlank()) {
            return null;
        }
        if (rawFilter.length() > 1024) {
            throw invalid("Filter expression exceeds maximum length of 1024 characters", "length");
        }

        List<Token> tokens = tokenize(rawFilter.trim());
        TokenCursor cursor = new TokenCursor(tokens);
        ValidatedMetadataFilter result = parseOrExpression(cursor);

        if (cursor.peek().type() != TokenType.EOF) {
            throw invalid("Unexpected trailing token in filter expression: '" + cursor.peek().value() + "'",
                    cursor.peek().value());
        }
        return result;
    }

    private ValidatedMetadataFilter parseOrExpression(TokenCursor cursor) {
        List<ValidatedMetadataFilter> branches = new ArrayList<>();
        branches.add(parseAndExpression(cursor));

        while (cursor.peek().type() == TokenType.OR) {
            cursor.consume();
            branches.add(parseAndExpression(cursor));
        }

        return branches.size() == 1
                ? branches.getFirst()
                : new Compound(LogicalCombinator.OR, branches);
    }

    private ValidatedMetadataFilter parseAndExpression(TokenCursor cursor) {
        List<ValidatedMetadataFilter> branches = new ArrayList<>();
        branches.add(parsePrimary(cursor));

        while (cursor.peek().type() == TokenType.AND) {
            cursor.consume();
            branches.add(parsePrimary(cursor));
        }

        return branches.size() == 1
                ? branches.getFirst()
                : new Compound(LogicalCombinator.AND, branches);
    }

    private ValidatedMetadataFilter parsePrimary(TokenCursor cursor) {
        if (cursor.peek().type() == TokenType.LPAREN) {
            cursor.consume();
            ValidatedMetadataFilter inner = parseOrExpression(cursor);
            if (cursor.peek().type() != TokenType.RPAREN) {
                throw invalid("Missing closing parenthesis ')' in filter expression", ")");
            }
            cursor.consume();
            return inner;
        }
        return parseCondition(cursor);
    }

    private Condition parseCondition(TokenCursor cursor) {
        Token fieldToken = cursor.peek();
        if (fieldToken.type() != TokenType.IDENT) {
            throw invalid("Expected metadata field name, got '" + fieldToken.value() + "'", fieldToken.value());
        }
        cursor.consume();

        // Suportam formatul compact "field:op:value" sau separat prin spatii "field op value"
        String rawIdent = fieldToken.value();
        String fieldName;
        String opRaw;
        String inlineValue = null;

        String[] colonParts = rawIdent.split(":", 3);
        if (colonParts.length >= 2) {
            fieldName = colonParts[0];
            opRaw = colonParts[1];
            if (colonParts.length == 3 && !colonParts[2].isEmpty()) {
                inlineValue = colonParts[2];
            }
        } else {
            fieldName = rawIdent;
            Token opToken = cursor.peek();
            if (opToken.type() != TokenType.IDENT) {
                throw invalid("Expected operator after field '" + fieldName + "'", opToken.value());
            }
            cursor.consume();
            opRaw = opToken.value();
        }

        if (!FIELD_SET.contains(fieldName)) {
            throw invalid("Metadata field '" + fieldName + "' is not allowed", fieldName);
        }

        Operator operator = switch (opRaw.toLowerCase(Locale.ROOT)) {
            case "eq", "=" -> Operator.EQ;
            case "ne", "!=" -> Operator.NE;
            case "in" -> Operator.IN;
            default -> throw invalid("Operator '" + opRaw + "' is not allowed", opRaw);
        };

        if (operator == Operator.IN) {
            if (inlineValue != null) {
                throw invalid("Operator 'in' requires parenthesized list of values: field:in:(val1,val2)", opRaw);
            }
            List<String> values = parseInValueList(cursor, fieldName);
            return new Condition(fieldName, Operator.IN, values);
        }

        String value = (inlineValue != null) ? inlineValue : parseSingleValue(cursor, fieldName);
        validateValue(value, fieldName);
        return new Condition(fieldName, operator, List.of(value));
    }

    private List<String> parseInValueList(TokenCursor cursor, String fieldName) {
        if (cursor.peek().type() != TokenType.LPAREN) {
            throw invalid("Expected '(' after 'in' operator for field '" + fieldName + "'", cursor.peek().value());
        }
        cursor.consume();

        List<String> values = new ArrayList<>();
        values.add(parseSingleValue(cursor, fieldName));

        while (cursor.peek().type() == TokenType.COMMA) {
            cursor.consume();
            values.add(parseSingleValue(cursor, fieldName));
        }

        if (cursor.peek().type() != TokenType.RPAREN) {
            throw invalid("Expected ')' closing value list for field '" + fieldName + "'", cursor.peek().value());
        }
        cursor.consume();

        for (String v : values) {
            validateValue(v, fieldName);
        }
        return values;
    }

    private String parseSingleValue(TokenCursor cursor, String fieldName) {
        Token valToken = cursor.peek();
        if (valToken.type() == TokenType.STRING || valToken.type() == TokenType.IDENT) {
            cursor.consume();
            return valToken.value();
        }
        throw invalid("Expected value for field '" + fieldName + "', got '" + valToken.value() + "'", valToken.value());
    }

    private void validateValue(String value, String fieldName) {
        if (value == null || value.isBlank() || !SAFE_VALUE_PATTERN.matcher(value).matches()) {
            throw invalid("Invalid or unsafe value '" + value + "' for field '" + fieldName + "'", String.valueOf(value));
        }
    }

    private List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = input.length();

        while (i < n) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '(') {
                tokens.add(new Token(TokenType.LPAREN, "("));
                i++;
            } else if (c == ')') {
                tokens.add(new Token(TokenType.RPAREN, ")"));
                i++;
            } else if (c == ',') {
                tokens.add(new Token(TokenType.COMMA, ","));
                i++;
            } else if (c == '\'' || c == '"') {
                char quote = c;
                i++;
                StringBuilder sb = new StringBuilder();
                boolean closed = false;
                while (i < n) {
                    char ch = input.charAt(i++);
                    if (ch == quote) {
                        closed = true;
                        break;
                    }
                    sb.append(ch);
                }
                if (!closed) {
                    throw invalid("Unterminated quoted string in filter expression", String.valueOf(quote));
                }
                tokens.add(new Token(TokenType.STRING, sb.toString()));
            } else {
                int start = i;
                while (i < n && !Character.isWhitespace(input.charAt(i))
                        && input.charAt(i) != '(' && input.charAt(i) != ')' && input.charAt(i) != ',') {
                    i++;
                }
                String word = input.substring(start, i);
                if ("AND".equalsIgnoreCase(word) || "&&".equals(word)) {
                    tokens.add(new Token(TokenType.AND, word));
                } else if ("OR".equalsIgnoreCase(word) || "||".equals(word)) {
                    tokens.add(new Token(TokenType.OR, word));
                } else {
                    tokens.add(new Token(TokenType.IDENT, word));
                }
            }
        }
        tokens.add(new Token(TokenType.EOF, "EOF"));
        return tokens;
    }

    private InvalidMetadataFilterException invalid(String message, String badToken) {
        return new InvalidMetadataFilterException(message, badToken, ALLOWED_FIELDS, ALLOWED_OPERATORS);
    }

    private static final class TokenCursor {
        private final List<Token> tokens;
        private int pos = 0;

        private TokenCursor(List<Token> tokens) {
            this.tokens = tokens;
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token consume() {
            return tokens.get(pos++);
        }
    }
}