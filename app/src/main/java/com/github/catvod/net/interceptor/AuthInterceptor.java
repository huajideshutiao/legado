package com.github.catvod.net.interceptor;

import androidx.annotation.NonNull;

import com.github.catvod.utils.Auth;
import com.google.common.net.HttpHeaders;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

public class AuthInterceptor implements Interceptor {

    private final ConcurrentHashMap<String, String> userMap;

    public AuthInterceptor() {
        userMap = new ConcurrentHashMap<>();
    }

    public void clear() {
        userMap.clear();
    }

    @NonNull
    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = check(chain.request());
        Response response = chain.proceed(request);
        if (response.code() != 401) return response;
        String host = request.url().host();
        String user = request.url().uri().getUserInfo();
        if (user == null) user = userMap.get(host);
        if (user == null) return response;
        response.close();
        String header = response.header(HttpHeaders.WWW_AUTHENTICATE);
        String auth = digest(header) ? Auth.digest(user, header, request) : Auth.basic(user);
        return chain.proceed(request.newBuilder().header(HttpHeaders.AUTHORIZATION, auth).build());
    }

    private boolean digest(String header) {
        return header != null && header.startsWith("Digest");
    }

    private Request check(Request request) {
        URI uri = request.url().uri();
        if (uri.getUserInfo() == null) return request;
        userMap.put(request.url().host(), uri.getUserInfo());
        return request.newBuilder().header(HttpHeaders.AUTHORIZATION, Auth.basic(uri.getUserInfo())).build();
    }
}
