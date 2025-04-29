package com.github.forax.framework.injector;

import java.util.Arrays;

public class TestClass {
    public static void main(String[] args) {
        Arrays.stream(String.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("set") || method.getName().startsWith("get") || method.getName().startsWith("is"))
                .forEach(method -> System.out.println(method.getName()));
    }
}
