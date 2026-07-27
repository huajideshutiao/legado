# Coroutine 链式协程 / FlowBus post 面 / appString 通道无反射入口。
# 书源规则实体经 GSON 反射读写（原 app 侧 -keep **.data.entities.** 覆盖，随迁登记）。
-keep class io.legado.app.data.entities.** { *; }

# 书源 JS 反射调用 AnalyzeByXPath 公开方法（XPath 规则解析），等价原 @Keep（照 QueryTTF 先例）。
-keep class io.legado.app.model.analyzeRule.AnalyzeByXPath { *; }

# 书源 JS 反射调用 AnalyzeByJSoup/AnalyzeByJSonPath 公开方法（JSoup/JsonPath 规则解析），等价原 @Keep。
-keep class io.legado.app.model.analyzeRule.AnalyzeByJSoup { *; }
-keep class io.legado.app.model.analyzeRule.AnalyzeByJSonPath { *; }

# JsVarName 原 @Keep（androidx 注解不入 commonMain），随 AppConst 下沉转等价 keep 规则。
-keep class io.legado.app.constant.AppConst$JsVarName { *; }

# JsURL 原 @Keep（androidx 注解不入 jvmAndAndroid），随下沉转等价 keep 规则（JS 桥反射访问属性）。
-keep class io.legado.app.utils.JsURL { *; }

# StrResponse 原 @Keep（androidx 注解不入 jvmAndAndroid），随下沉转等价 keep 规则（JS 桥反射访问 body/url 等）。
-keep class io.legado.app.help.http.StrResponse { *; }

# crypto 三件套 (AsymmetricCryptoAndroid/SignAndroid/SymmetricCryptoAndroid) 原 @Keep（androidx 注解不入 jvmAndAndroid），
# 随下沉转等价 keep 规则（JS 桥反射调用 encrypt/decrypt 等方法）。
# KMP 化: 原 jvmAndAndroidMain `AsymmetricCrypto`/`Sign` class 改名为 `AsymmetricCryptoAndroid`/`SignAndroid`
# (仿 SymmetricCryptoAndroid 命名约定), 避免与 commonMain 同包同名 interface 在合并编译时冲突。
-keep class io.legado.app.help.crypto.AsymmetricCryptoAndroid { *; }
-keep class io.legado.app.help.crypto.SignAndroid { *; }
-keep class io.legado.app.help.crypto.SymmetricCryptoAndroid { *; }

# GithubRelease/Asset 原 @Keep（androidx 注解不入 jvmAndAndroid），随下沉转等价 keep 规则。
-keep class io.legado.app.help.update.GithubRelease { *; }
-keep class io.legado.app.help.update.Asset { *; }

# app 侧 GSON 反射读写这两个类（CbzFile 缓存）
-keep class io.legado.app.model.fileBook.ZipEntry { *; }
-keep class io.legado.app.model.fileBook.ZipImageCache { *; }

# 书源 JS 反射调用 QueryTTF 的公开方法（字体反混淆），等价原 @Keep
-keep class io.legado.app.model.analyzeRule.QueryTTF { *; }

# DirectLinkUploadRule 原 @Keep（androidx 注解不入 commonMain），随下沉转等价 keep 规则
# （GSON 反射读写直链上传规则配置 directLinkUploadRule.json）。
-keep class io.legado.app.help.DirectLinkUploadRule { *; }

# RemoteBook 原 @Keep（androidx 注解不入 commonMain），随其下沉 commonMain 转等价 keep 规则。
# 注: RemoteBookManager 已下沉 commonMain (downloadRemoteBook 改返回 String, 不再依赖 android.net.Uri), abstract 类无反射需求, 不在本 keep 范围。
-keep class io.legado.app.model.remote.RemoteBook { *; }
