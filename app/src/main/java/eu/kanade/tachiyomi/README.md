# Mihon/Tachiyomi 扩展宿主兼容层（eu.kanade.tachiyomi 原包名）

扩展 APK 按 `eu.kanade.tachiyomi.*` 包名以 compileOnly 编译，运行时这些类必须由宿主以逐字一致的 FQCN/签名提供——本目录即该宿主面。**包名/方法签名不得改动**，改动即破坏全部第三方扩展。

## 结构

- `source/`：Source、CatalogueSource、HttpSource、ParsedHttpSource、ConfigurableSource、SourceFactory、UnmeteredSource（漫画，lib 1.4 与 1.6 共用）；`source/model/`：SManga、SChapter、Page、Filter 系、FilterList；`source/online/`。
- `animesource/`：Aniyomi 视频扩展面（AnimeSource、AnimeCatalogueSource、AnimeHttpSource、model/SAnime、SEpisode、Video、Track、TimeStamp）。
- `network/`：NetworkHelper、GET/POST/HEAD/PUT/DELETE、拦截器链（UncaughtException/UserAgent/Cloudflare 前插——KeiSource 按 simpleName 强校验）、Cloudflare WebView 挑战。
- `util/`：String.asJsoup 等（对接 data 模块 ksoup 门面）。
- AppInfo / ExtensionCompat / Injekt 注册：uy.kohesive.injekt 用 null2264 fork（kohesive 原版 1.16.1 缺扩展所需包面），启动注册在 App.kt。

## 关键决策（有上游证据）

- **KeiSource（lib 1.6）不在此提供**：位于 keiyoushi extensions-source 的 core/ 模块、打进每个扩展 APK 自带；宿主重复提供会以父优先委派遮蔽扩展自带副本。`source/` 只提供到 Source/HttpSource 系。
- **SortState 不存在于上游**（mihon 与两代 extensions-lib 均无）：排序筛选实际类型 `Filter.Sort.Selection(index, ascending)`，已按真实形状提供。
- **okhttp 5.4 源码级核验**：`Response.body` 非空、`CacheControl.Builder.maxAge(Duration)` 存在、`OkHttpClient.Builder` 无 `interceptors(List)` setter（须 `interceptors().addAll(0, …)` 前插）。

## shim 兜底（引真依赖后必须删除，文件头已注明）

- `okhttp3/brotli/`、`okhttp3/zstd/`：okhttp-brotli / zstd 未引入。
- `androidx/preference/` ×9：androidx.preference 未引入。
- `kotlinx/serialization/json/okio/Okio.kt`：json-okio 未引入。

## 遗留豁口

- org.jsoup 门面不全（597 个扩展文件引用）：见 `data/src/commonMain/kotlin/org/jsoup/README.md`。
- rxjava 1.3.8（22 文件）、zstd-kmp-okio（1 文件）未引入。
- 全层编译与真实扩展加载验证由主代理收尾。
