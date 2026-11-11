package org.eclipse.jdt.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Compile-time stub of the Eclipse JDT null annotation (class retention, not needed at runtime). */
@Retention(RetentionPolicy.CLASS)
public @interface NonNullByDefault {
    DefaultLocation[] value() default {};
}
