package com.github.catvod.net;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.util.Objects;

public class ProxyAuthenticator extends Authenticator {

    private final OkProxySelector selector;

    public ProxyAuthenticator(OkProxySelector selector) {
        this.selector = selector;
    }

    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        String userInfo = findUserInfo(getRequestingHost());
        if (userInfo == null || !userInfo.contains(":")) return null;
        int index = userInfo.indexOf(':');
        return new PasswordAuthentication(userInfo.substring(0, index), userInfo.substring(index + 1).toCharArray());
    }

    private String findUserInfo(String proxyHost) {
        return selector.getProxy().stream().map(item -> item.getUserInfo(proxyHost)).filter(Objects::nonNull).findFirst().orElse(null);
    }
}
