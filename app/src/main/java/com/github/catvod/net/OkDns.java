package com.github.catvod.net;

import androidx.annotation.NonNull;

import com.github.catvod.bean.Doh;
import com.github.catvod.utils.Util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import okhttp3.Dns;
import okhttp3.HttpUrl;

/**
 * TVBox 壳 DNS 解析器: FQCN / 构造器 / 全部 public 方法签名逐字对齐 FongMi catvod 模块的
 * net.OkDns (jar 经 Spider.safeDns() -> OkHttp.dns() 拿到它, 再调 addAll/setDoh/clear)。
 *
 * ============================ 与 FongMi 的一处已知差异 ============================
 * FongMi 的 lookup() 用 okhttp3.dnsoverhttps.DnsOverHttps 走真正的 DoH 传输:
 *     private volatile DnsOverHttps doh;
 *     setDoh(Doh) -> new DnsOverHttps.Builder().client(new OkHttpClient()).url(url).bootstrapDnsHosts(item.getHosts()).build()
 *     lookup()    -> (doh != null ? doh : Dns.SYSTEM).lookup(get(hostname))
 * 本宿主 app 模块**没有** okhttp-dnsoverhttps 依赖 (只有 okhttp/okio/gson/guava),
 * 类路径上没有 okhttp3.dnsoverhttps.DnsOverHttps 也没有 okhttp3.logging.HttpLoggingInterceptor;
 * 照搬会让 :app 直接编译失败, 所以此处:
 *   - 保留 setDoh(Doh) / setDoh(Supplier<Doh>) / clear() / addAll(List) / lookup(String) 全部签名与语义
 *   - 保留 doh 字段 (已配置的 DoH 项) 与 supplier 惰性求值逻辑, 便于日后一行接回 DnsOverHttps
 *   - lookup() 退化为系统 DNS: hosts 覆盖表 (addAll) 仍然完全生效, 只是不再经 DoH 传输
 * 即: DoH 传输在本宿主处于「API 存在、行为降级为系统 DNS」状态, 不会抛 NoSuchMethodError/NoClassDefFoundError。
 * 若要恢复与 FongMi 逐字一致的 DoH 传输, 只需在 app/build.gradle.kts 加
 *     implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:<与 okhttp 同版本>")
 * 后把 lookup() 的末行换回 FongMi 原句 (见下方注释)。
 * ==============================================================================
 */
public class OkDns implements Dns {

    private final ConcurrentHashMap<String, String> map;
    private volatile Supplier<Doh> supplier;
    private volatile Doh doh;

    public OkDns() {
        this.map = new ConcurrentHashMap<>();
    }

    public synchronized void setDoh(Doh item) {
        HttpUrl url = HttpUrl.parse(item.getUrl());
        this.doh = url == null ? null : item;
        this.supplier = null;
    }

    public synchronized void setDoh(Supplier<Doh> supplier) {
        this.supplier = supplier;
    }

    public void clear() {
        map.clear();
    }

    public void addAll(List<String> hosts) {
        map.putAll(hosts.stream().filter(Objects::nonNull).map(host -> host.split("=", 2)).filter(splits -> splits.length == 2).collect(Collectors.toMap(s -> s[0].trim(), s -> s[1].trim(), (oldHost, newHost) -> newHost)));
    }

    private String get(String hostname) {
        String target = map.get(hostname);
        if (target != null) return target;
        for (Map.Entry<String, String> entry : map.entrySet()) if (Util.containOrMatch(hostname, entry.getKey())) return entry.getValue();
        return hostname;
    }

    @NonNull
    @Override
    public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
        Supplier<Doh> supplier = this.supplier;
        if (supplier != null) initDoh(supplier);
        // FongMi 原句 (需 okhttp-dnsoverhttps 依赖, 本宿主暂缺, 见类注释):
        //   return (doh != null ? doh : Dns.SYSTEM).lookup(get(hostname));
        // DoH 传输降级为系统 DNS; hosts 覆盖表 (addAll) 仍完全生效。
        return Dns.SYSTEM.lookup(get(hostname));
    }

    private synchronized void initDoh(Supplier<Doh> supplier) {
        if (supplier != this.supplier) return;
        setDoh(supplier.get());
    }
}
