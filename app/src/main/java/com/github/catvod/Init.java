package com.github.catvod;

import android.content.Context;

import java.lang.ref.WeakReference;

/**
 * TVBox 壳上下文持有者 (签名对齐 FongMi catvod 模块; 宿主在装载前 Init.set(appContext))。
 */
public class Init {

    private WeakReference<Context> context;

    private static Init get() {
        return Loader.INSTANCE;
    }

    public static void set(Context context) {
        get().context = new WeakReference<>(context);
    }

    public static Context context() {
        return get().context.get();
    }

    private static class Loader {
        static volatile Init INSTANCE = new Init();
    }
}
