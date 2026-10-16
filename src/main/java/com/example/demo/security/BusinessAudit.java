package com.example.demo.security;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface BusinessAudit {
    String operation();
    String target();
    int idArgument() default -1;
}
