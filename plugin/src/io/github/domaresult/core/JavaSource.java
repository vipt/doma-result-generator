/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

public final class JavaSource {
    private JavaSource() {}

    /** Mask a UTF BOM for JDT while keeping every source offset and the original text intact. */
    public static String forParser(String source) {
        return source.startsWith("\uFEFF") ? " " + source.substring(1) : source;
    }
}
