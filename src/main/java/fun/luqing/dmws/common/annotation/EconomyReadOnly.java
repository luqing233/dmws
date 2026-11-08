package fun.luqing.dmws.common.annotation;

import java.lang.annotation.*;

/** A query must not implicitly register a player via the transport presence aspect. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EconomyReadOnly { }
