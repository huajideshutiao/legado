package com.github.catvod.utils;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * TVBox 壳 shell 执行工具: 签名与行为逐字对齐 FongMi catvod 模块的 utils.Shell。
 * 目前唯一调用方是 Path.create(File) 里的 Shell.exec("chmod 777 " + file)。
 *
 * 与 FongMi 的唯一差异: FongMi 用 com.orhanobut.logger, 本宿主不引入该依赖,
 * Logger.t(TAG).d(fmt, args) 等价替换为 android.util.Log.d(TAG, String.format(...))。
 */
public class Shell {

    private static final String TAG = Shell.class.getSimpleName();

    public static String exec(String command) {
        try {
            StringBuilder sb = new StringBuilder();
            Process p = Runtime.getRuntime().exec(command);
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            Log.d(TAG, String.format("Shell command '%s' with exit code '%s'", command, p.waitFor()));
            return Util.substring(sb.toString());
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }
}
