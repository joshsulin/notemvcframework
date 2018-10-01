package com.josh.mvcframework.annotation;

import java.lang.annotation.*;

/**
 * Created by sulin on 2018/9/30.
 */
@Target({ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface GPRequestParam {
    String value() default "";
}
