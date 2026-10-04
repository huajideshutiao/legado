package com.github.catvod.bean;

import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

/**
 * TVBox 壳防挂头模型 (签名对齐 FongMi catvod 模块 bean.Header)。
 */
public class Header {

    @SerializedName("host")
    private String host;
    @SerializedName("header")
    private JsonElement header;

    public static List<Header> arrayFrom(JsonElement element) {
        try {
            Type listType = TypeToken.getParameterized(List.class, Header.class).getType();
            List<Header> items = new Gson().fromJson(element, listType);
            return items == null ? Collections.emptyList() : items;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public String getHost() {
        return TextUtils.isEmpty(host) ? "" : host;
    }

    public JsonElement getHeader() {
        return header;
    }
}
