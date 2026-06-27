# Add project specific ProGuard rules here.

## quickjs-kt (Kotlin Multiplatform QuickJS 绑定)
-keep class com.dokar.quickjs.** { *; }
-keep class com.dokar.quickjs.binding.** { *; }

## 通过反射绑定注入到 JS 的业务类需要保留
-keep class io.legado.app.help.JsExtensions { *; }
-keep class io.legado.app.help.JsEncodeUtils { *; }
-keep class io.legado.app.data.entities.** { *; }
-keep class io.legado.app.help.http.CookieStore { *; }
-keep class io.legado.app.help.CacheManager { *; }

## 用户的 JS 调用入口(AnalyzeRule/AnalyzeUrl/BaseSource 的 public 方法)
-keep class io.legado.app.model.analyzeRule.AnalyzeRule { public *; }
-keep class io.legado.app.model.analyzeRule.AnalyzeUrl { public *; }
-keep class io.legado.app.data.entities.BaseSource { public *; }
