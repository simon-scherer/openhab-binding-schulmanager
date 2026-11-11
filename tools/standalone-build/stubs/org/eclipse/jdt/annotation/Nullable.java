package org.eclipse.jdt.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Compile-time stub of the Eclipse JDT null annotation (class retention, not needed at runtime). */
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.TYPE_USE })
public @interface Nullable {

}
