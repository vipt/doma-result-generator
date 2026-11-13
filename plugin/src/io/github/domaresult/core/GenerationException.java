/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

/** Validation failure whose message is safe to show in the generator UI. */
public final class GenerationException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public GenerationException(String message) { super(message); }
}
