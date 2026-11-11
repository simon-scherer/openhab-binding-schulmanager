package org.osgi.service.component.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Compile-time stub; the standalone build ships the generated component XML in resources/OSGI-INF. */
@Retention(RetentionPolicy.CLASS)
public @interface Component {
    String[] configurationPid() default {};

    Class<?>[] service() default {};
}
