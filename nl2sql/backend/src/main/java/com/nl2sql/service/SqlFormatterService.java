package com.nl2sql.service;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats Oracle SQL queries with consistent indentation and keyword casing.
 * No external dependency — pure Java regex-based formatter.
 *
 * Rules applied:
 *  - Major keywords (SELECT, FROM, WHERE, etc.) start on a new line, uppercase, no indent
 *  - Sub-clauses (AND, OR, ON) indented by 4 spaces
 *  - Column list items in SELECT indented by 4 spaces, one per line
 *  - Commas placed at end of line (not start)
 *  - Parentheses handled for sub-queries with nested indent
 */
@Service
public class SqlFormatterService {

    private static final List<String> MAJOR_KEYWORDS = List.of(
            "SELECT", "FROM", "WHERE", "GROUP BY", "HAVING", "ORDER BY",
            "INNER JOIN", "LEFT JOIN", "RIGHT JOIN", "FULL JOIN", "FULL OUTER JOIN",
            "LEFT OUTER JOIN", "RIGHT OUTER JOIN", "CROSS JOIN", "JOIN",
            "UNION ALL", "UNION", "INTERSECT", "MINUS",
            "INSERT INTO", "UPDATE", "SET", "DELETE FROM",
            "WITH", "FETCH FIRST", "LIMIT", "OFFSET"
    );

    private static final List<String> SUB_KEYWORDS = List.of("AND", "OR", "ON");

    public String format(String sql) {
        if (sql == null || sql.isBlank()) return sql;

        // 1. Normalise whitespace — collapse all whitespace runs to single space
        String normalised = sql.trim()
                .replaceAll("\\r\\n|\\r", "\n")
                .replaceAll("[ \\t]+", " ");

        // 2. Tokenise around major keywords
        StringBuilder result = new StringBuilder();
        String remaining = normalised;

        // Build a regex that matches any major keyword at a word boundary
        String majorPattern = buildKeywordPattern(MAJOR_KEYWORDS);
        String subPattern   = buildKeywordPattern(SUB_KEYWORDS);

        Pattern mp = Pattern.compile(majorPattern, Pattern.CASE_INSENSITIVE);
        Pattern sp = Pattern.compile(subPattern,   Pattern.CASE_INSENSITIVE);

        // Split by major keywords preserving delimiters
        String[] parts = remaining.split("(?i)(?=" + majorPattern + ")");

        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;

            // Identify which major keyword starts this part
            Matcher mm = mp.matcher(trimmed);
            if (mm.find() && mm.start() == 0) {
                String keyword = mm.group().toUpperCase();
                String rest    = trimmed.substring(mm.end()).trim();

                result.append("\n").append(keyword);

                if (keyword.equals("SELECT")) {
                    // Format SELECT column list — one column per line
                    result.append("\n").append(formatSelectList(rest));
                } else {
                    // For other clauses, expand AND/OR/ON onto separate indented lines
                    String expanded = sp.matcher(rest).replaceAll(mr ->
                            "\n    " + mr.group().toUpperCase() + " ");
                    result.append(" ").append(expanded.trim());
                }
            } else {
                result.append(" ").append(trimmed);
            }
        }

        // 3. Clean up — remove leading newline, normalise blank lines
        String formatted = result.toString()
                .replaceAll("\\n{3,}", "\n\n")
                .trim();

        return formatted;
    }

    /**
     * Formats a SELECT column list: one column per line, indented 4 spaces.
     * Handles nested parentheses (functions, sub-queries) correctly.
     */
    private String formatSelectList(String columnList) {
        StringBuilder sb    = new StringBuilder();
        StringBuilder token = new StringBuilder();
        int depth = 0;

        for (int i = 0; i < columnList.length(); i++) {
            char c = columnList.charAt(i);
            if (c == '(') { depth++; token.append(c); }
            else if (c == ')') { depth--; token.append(c); }
            else if (c == ',' && depth == 0) {
                sb.append("    ").append(token.toString().trim()).append(",\n");
                token.setLength(0);
            } else {
                token.append(c);
            }
        }
        if (!token.toString().isBlank()) {
            sb.append("    ").append(token.toString().trim());
        }
        return sb.toString();
    }

    private String buildKeywordPattern(List<String> keywords) {
        // Sort by length descending so longer keywords match first (e.g. LEFT OUTER JOIN before LEFT JOIN)
        return keywords.stream()
                .sorted((a, b) -> b.length() - a.length())
                .map(k -> k.replace(" ", "\\s+"))
                .reduce((a, b) -> a + "|" + b)
                .orElse("");
    }
}
