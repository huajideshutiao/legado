package com.github.catvod.bean;

import android.net.Uri;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * TVBox 壳代理规则模型: FQCN / 字段 / 方法签名逐字对齐 FongMi catvod 模块的 bean.Proxy
 * (被 net.OkProxySelector / net.OkAuthenticator / net.ProxyAuthenticator 引用)。
 *
 * 与 FongMi 的唯一差异 (纯 API 级别, 语义等价):
 * FongMi 用 Stream.toList() (Java 16, Android API 34), 本宿主 app 模块**未启用**
 * core library desugaring (无 coreLibraryDesugaring 依赖, APK 内无 j$/util/** 类),
 * minSdk 24 上会 NoSuchMethodError, 故等价改写为 Collectors.toList() (API 24)。
 */
public class Proxy implements Comparable<Proxy> {

    @SerializedName("name")
    private String name;
    @SerializedName("hosts")
    private List<String> hosts;
    @SerializedName("urls")
    private List<String> urls;

    private List<java.net.Proxy> proxies;
    private List<Uri> uris;
    private boolean wildcard;

    public static List<Proxy> arrayFrom(JsonElement element) {
        try {
            Type listType = TypeToken.getParameterized(List.class, Proxy.class).getType();
            List<Proxy> items = new Gson().fromJson(element, listType);
            return items == null ? Collections.emptyList() : items;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public void init() {
        wildcard = getHosts().stream().anyMatch(host -> host.contains("*"));
        uris = getUrls().stream().map(Uri::parse).filter(this::isValid).collect(Collectors.toList());
        proxies = uris.stream().map(this::create).filter(Objects::nonNull).collect(Collectors.toList());
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? "" : name;
    }

    public List<String> getHosts() {
        return hosts == null ? Collections.emptyList() : hosts;
    }

    public List<String> getUrls() {
        return urls == null ? Collections.emptyList() : urls;
    }

    public List<java.net.Proxy> getProxies() {
        return proxies == null ? Collections.emptyList() : proxies;
    }

    public String getUserInfo(String host) {
        return getUserInfo(host, "socks");
    }

    public String getUserInfo(String host, String scheme) {
        return uris.stream().filter(uri -> isScheme(uri, scheme) && host.equalsIgnoreCase(uri.getHost())).map(Uri::getUserInfo).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private boolean isValid(Uri uri) {
        return uri.getScheme() != null && uri.getHost() != null && uri.getPort() > 0;
    }

    private java.net.Proxy create(Uri uri) {
        InetSocketAddress address = InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort());
        if (isScheme(uri, "http")) return new java.net.Proxy(java.net.Proxy.Type.HTTP, address);
        if (isScheme(uri, "socks")) return new java.net.Proxy(java.net.Proxy.Type.SOCKS, address);
        return null;
    }

    private boolean isScheme(Uri uri, String scheme) {
        return uri.getScheme() != null && uri.getScheme().startsWith(scheme);
    }

    @Override
    public int compareTo(Proxy other) {
        return Boolean.compare(this.wildcard, other.wildcard);
    }
}
