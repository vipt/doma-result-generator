/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

import java.sql.SQLException;

/** An actionable generator error, without driver-provided connection details. */
public final class MetadataException extends SQLException {
    private static final long serialVersionUID = 1L;

    public MetadataException(String message) { super(message); }
}
