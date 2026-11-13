/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

public record ResultColumn(String label, String javaType) {
    public ResultColumn {
        validateLabel(label);
        if (javaType == null || javaType.isBlank()) {
            throw new GenerationException("A result column has no Java type.");
        }
    }

    static void validateLabel(String label) {
        if (label == null || label.isBlank()) {
            throw new GenerationException("A result column has no label. Add an AS alias.");
        }
        // Doma 2 processors embed the label in generated Java string literals.
        // Reject labels they cannot safely reproduce, even if our own source escapes them.
        if (label.chars().anyMatch(c -> c == '"' || c == '\\' || Character.isISOControl(c))) {
            throw new GenerationException("A result label contains quotes, backslashes or control characters. "
                    + "Use a simple AS alias that Doma 2 can process.");
        }
    }
}
