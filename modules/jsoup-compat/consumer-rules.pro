# jsoup-compat consumer rules
# js 反射调用 org.jsoup.Jsoup.connect / org.jsoup.Connection.Response 等需要保留类名与方法签名
-keep class org.jsoup.** { *; }
