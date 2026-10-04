package com.github.catvod;

import com.github.catvod.utils.Util;

/**
 * TVBox 本地代理地址壳类 (签名对齐 FongMi catvod 模块)。
 * 本宿主本轮未起 :9978 代理服务, getPort 为负时 jar 内构造的代理 URL 不可达。
 */
public class Proxy {

    private static int port = -1;

    public static void set(int port) {
        Proxy.port = port;
    }

    public static int getPort() {
        return port;
    }

    public static String getUrl(boolean local) {
        return "http://" + (local ? "127.0.0.1" : Util.getIp()) + ":" + getPort() + "/proxy";
    }
}
