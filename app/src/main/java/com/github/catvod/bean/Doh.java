package com.github.catvod.bean;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DoH (DNS over HTTPS) 配置模型, FQCN 与字段/方法签名逐字对齐 FongMi catvod 模块的 bean.Doh
 * (jar 以 compileOnly 方式按该 FQCN 链接, 见 net.OkDns.setDoh)。
 *
 * 与 FongMi 的唯一差异: FongMi 的 Doh.get(Context) 读的是它自己 catvod 模块资源里的
 * R.array.doh_name / R.array.doh_url (见 FongMi catvod/src/main/res/values/strings.xml);
 * 本宿主没有 catvod 资源模块 (namespace=io.legado.app, com.github.catvod 包内无 R),
 * 因此 get(Context) 改为返回空列表 —— 宿主不预置 DoH 清单, 清单由用户配置 (VodConfig.doh)
 * 经 Doh.arrayFrom / Doh.objectFrom 传入, 这两个入口逐字照搬 FongMi, 是 OkDns 实际用到的面。
 */
public class Doh {

    @SerializedName("name")
    private String name;
    @SerializedName("url")
    private String url;
    @SerializedName("ips")
    private List<String> ips;

    /**
     * FongMi 从 catvod 模块资源数组读取内置 DoH 清单; 本宿主无该资源模块, 返回空列表。
     * (调用方 FongMi app 侧 VodConfig.java:211; 宿主侧无调用方。)
     */
    public static List<Doh> get(android.content.Context context) {
        return new ArrayList<>();
    }

    public static Doh objectFrom(String str) {
        Doh item = new Gson().fromJson(str, Doh.class);
        return item == null ? new Doh() : item;
    }

    public static List<Doh> arrayFrom(JsonElement element) {
        try {
            Type listType = TypeToken.getParameterized(List.class, Doh.class).getType();
            List<Doh> items = new Gson().fromJson(element, listType);
            return items == null ? new ArrayList<>() : items;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public Doh name(String name) {
        this.name = name;
        return this;
    }

    public Doh url(String url) {
        this.url = url;
        return this;
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? "" : name;
    }

    public String getUrl() {
        return TextUtils.isEmpty(url) ? "" : url;
    }

    public List<String> getIps() {
        return ips == null ? Collections.emptyList() : ips;
    }

    public List<InetAddress> getHosts() {
        try {
            List<InetAddress> list = new ArrayList<>();
            for (String ip : getIps()) list.add(InetAddress.getByName(ip));
            return list.isEmpty() ? null : list;
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Doh it)) return false;
        return getUrl().equals(it.getUrl());
    }

    @Override
    public int hashCode() {
        return getUrl().hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
