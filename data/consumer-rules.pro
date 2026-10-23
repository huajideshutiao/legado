# 书源规则实体经 GSON 反射读写（原 app 侧 -keep **.data.entities.** 覆盖, 随迁登记）。
-keep class io.legado.app.data.entities.** { *; }

# 书源 JS 反射调用 AnalyzeByXPath 公开方法（XPath 规则解析）, 等价原 @Keep。
-keep class io.legado.app.model.analyzeRule.AnalyzeByXPath { *; }

# 书源 JS 反射调用 AnalyzeByJSoup/AnalyzeByJSonPath 公开方法（JSoup/JsonPath 规则解析）, 等价原 @Keep。
-keep class io.legado.app.model.analyzeRule.AnalyzeByJSoup { *; }
-keep class io.legado.app.model.analyzeRule.AnalyzeByJSonPath { *; }

# StrResponse 原 @Keep（androidx 注解不入 commonMain）, JS 桥反射访问 body/url 等。
-keep class io.legado.app.help.http.StrResponse { *; }

# crypto 三件套 (AsymmetricCryptoAndroid/SignAndroid/SymmetricCryptoAndroid) 原 @Keep,
# JS 桥反射调用 encrypt/decrypt 等方法。
-keep class io.legado.app.help.crypto.AsymmetricCryptoAndroid { *; }
-keep class io.legado.app.help.crypto.SignAndroid { *; }
-keep class io.legado.app.help.crypto.SymmetricCryptoAndroid { *; }

# app 侧 GSON 反射读写这两个类（CbzFile 缓存）。
-keep class io.legado.app.model.fileBook.ZipEntry { *; }
-keep class io.legado.app.model.fileBook.ZipImageCache { *; }

# 书源 JS 反射调用 QueryTTF 的公开方法（字体反混淆）, 等价原 @Keep。
-keep class io.legado.app.model.analyzeRule.QueryTTF { *; }

# DirectLinkUploadRule 原 @Keep（GSON 反射读写直链上传规则配置 directLinkUploadRule.json）。
-keep class io.legado.app.help.DirectLinkUploadRule { *; }

# RemoteBook 原 @Keep（GSON 反射读写远程书信息）。
-keep class io.legado.app.model.remote.RemoteBook { *; }

# Tachiyomi/Aniyomi 扩展宿主面：扩展 APK 以 compileOnly 引用宿主类型，并继承/覆盖这些类型的成员。
# R8 的成员 final 化会把可覆盖成员改成 final，扩展类加载阶段即 LinkageError(overrides final method)，
# 故扩展实现/继承/构造的类型禁止优化（规则面与 Aniyomi 随源码发布的扩展保留规则同构）。
# 成员范围取全部而非 public protected：宿主启用混淆，扩展经反射替换的请求头委托字段为私有。
-keep class eu.kanade.tachiyomi.source.model.** { *; }
-keep class eu.kanade.tachiyomi.source.online.** { *; }
-keep class eu.kanade.tachiyomi.source.** extends eu.kanade.tachiyomi.source.Source { *; }

-keep class eu.kanade.tachiyomi.animesource.model.** { *; }
-keep class eu.kanade.tachiyomi.animesource.online.** { *; }
-keep class eu.kanade.tachiyomi.animesource.** extends eu.kanade.tachiyomi.animesource.AnimeSource { *; }
