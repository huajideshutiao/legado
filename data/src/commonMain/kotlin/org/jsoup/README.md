# org.jsoup 门面（ksoup 移植，供 Mihon 扩展使用）

本目录按 `org.jsoup` 原包名提供 jsoup 兼容层（底层 ksoup），Mihon/Tachiyomi 扩展 537 个文件直接 import org.jsoup。

## 类面状态

`nodes/{Node,Element,Document,TextNode,Comment,DataNode}`、`select/{Elements,Evaluator,Collector,QueryParser}`、`parser.Parser`（unescapeEntities）已按 keiyoushi 全仓扩展真实用法（537 import / 方法调用统计）逐字补齐，全部委托底层 ksoup；`parse(InputStream,…)` 按 jsoup DataUtil charset 探测语义在 jvmAndAndroid actual 自实现（ksoup-io 不在依赖树）。

## 已知豁口（扩展零引用，缺失即显式失败非静默）

- `Tag`、`Attributes`、`DocumentType`、`Document.OutputSettings/forms`、`Selector`、`NodeVisitor/NodeTraversor`、`Jsoup.connect`、`parse(File)`
- 宿主侧调用入口：`app/src/main/java/eu/kanade/tachiyomi/util/JsoupExtensions.kt`（Document/Element typealias 指向本门面 + String.asJsoup）
