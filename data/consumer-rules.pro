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
