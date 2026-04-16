package ch.admin.bit.jeap.opensearch.registry.generator;

import lombok.experimental.UtilityClass;
import org.springframework.util.StringUtils;

/**
 * Maps OpenSearch field types to Java types.
 */
@UtilityClass
public class OpenSearchTypeMapper {

    public static String toJavaType(String openSearchType) {
        return switch (openSearchType.toLowerCase()) {
            case "keyword", "text", "wildcard", "constant_keyword" -> "String";
            case "integer", "short", "byte" -> "Integer";
            case "long" -> "Long";
            case "float", "half_float" -> "Float";
            case "double", "scaled_float" -> "Double";
            case "boolean" -> "Boolean";
            case "date" -> "java.time.Instant";
            case "binary" -> "String";
            default -> "Object";
        };
    }

    /**
     * Converts snake_case field name to camelCase Java field name.
     */
    public static String toCamelCase(String snakeName) {
        if (!StringUtils.hasText(snakeName)) {
            return snakeName;
        }
        StringBuilder result = new StringBuilder();
        boolean nextUpper = false;
        for (char c : snakeName.toCharArray()) {
            if (c == '_') {
                nextUpper = true;
            } else {
                result.append(nextUpper ? Character.toUpperCase(c) : c);
                nextUpper = false;
            }
        }
        return result.toString();
    }

    /**
     * Converts a field name to a Java class name (PascalCase).
     */
    public static String toClassName(String fieldName) {
        String camel = toCamelCase(fieldName);
        if (!StringUtils.hasText(camel)) {
            return camel;
        }
        return Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
    }
}
