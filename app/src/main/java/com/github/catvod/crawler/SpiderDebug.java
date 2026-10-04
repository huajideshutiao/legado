package com.github.catvod.crawler;

import android.text.TextUtils;
import android.util.Log;

/**
 * TVBox 生态壳日志类 (签名对齐 FongMi catvod 模块; 输出走 android.util.Log,
 * FongMi 原实现为 com.orhanobut.logger, 本宿主不引入)。
 */
public class SpiderDebug {

    private static final String TAG = SpiderDebug.class.getSimpleName();

    public static void log(Throwable th) {
        if (th != null) Log.e(TAG, "", th);
    }

    public static void log(String msg) {
        if (!TextUtils.isEmpty(msg)) Log.d(TAG, msg);
    }

    public static void log(String tag, String msg, Object... args) {
        if (!TextUtils.isEmpty(msg)) Log.d(tag, String.format(msg, args));
    }
}
