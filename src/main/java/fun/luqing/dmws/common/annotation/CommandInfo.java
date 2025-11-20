package fun.luqing.dmws.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CommandInfo {
    String cmd() default "";          // 完整匹配指令
    String startWith() default "";    // 前缀匹配指令
    String desc();                    // 指令描述
}

