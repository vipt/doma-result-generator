/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.core;

public final class ExpressionFixture {
    public static int calls;
    public static boolean enabled() { calls++; return true; }
    public static String orderBy() { calls++; return "ORDER BY name"; }
    public static final class Filter {
        private String name;
        private boolean enabled;
        public String getName() { return name; }
        public boolean isEnabled() { return enabled; }
    }
}
