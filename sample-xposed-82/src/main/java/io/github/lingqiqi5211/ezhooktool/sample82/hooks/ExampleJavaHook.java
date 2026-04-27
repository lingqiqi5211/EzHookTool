package io.github.lingqiqi5211.ezhooktool.sample82.hooks;

import android.app.Application;
import android.util.Log;

import java.lang.reflect.Method;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.Hooks;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

public final class ExampleJavaHook extends BaseHook {
    public static final ExampleJavaHook INSTANCE = new ExampleJavaHook();

    private ExampleJavaHook() {
    }

    @Override
    public void init() {
        final Method onCreate;
        try {
            onCreate = Application.class.getDeclaredMethod("onCreate");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Cannot find Application#onCreate", e);
        }

        Hooks.createHook(onCreate, new IMethodHook() {
            @Override
            public void before(HookParam param) {
                Log.i(getName(), "Hello, Java before hook!");
            }

            @Override
            public void after(HookParam param) {
                Log.i(getName(), "Hello, Java after hook!");
            }
        });
    }

    @Override
    public String getName() {
        return "ExampleJavaHook";
    }
}
