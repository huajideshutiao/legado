# 内嵌第三方库来源说明（vendored）

本目录的源码不是本项目编写，是逐文件复制进来的第三方库副本，许可为 **Apache-2.0**，
许可证原文见同目录 `LICENSE`（与上游逐字节相同，11338 字节）。

| 项 | 值 |
|---|---|
| 上游仓库 | https://github.com/snappdevelopment/JsonTree |
| 对应版本 | `v2.8.0`（2026-09-19 发布） |
| 来源产物 | Maven Central `com.sebastianneubauer.jsontree:jsontree:2.8.0` 的 sources jar |
| 复制的文件 | `JsonTree.kt` `JsonTreeElement.kt` `JsonTreeParser.kt` `JsonTreeParserState.kt` `TreeColors.kt` `TreeState.kt` `CollapsableType.kt` `util/AnnotatedText.kt` `util/IdGenerator.kt` `util/JsonTreeElementExtensions.kt` |
| 为何内嵌而非依赖坐标 | 上游只发布到 Kotlin 2.4 工具链（2.8.0 的 iOS klib 为 abi 2.4.0，本项目 Kotlin 2.3.20 消费不了），且本模块需支持 Android / 桌面 JVM / iOS native / 鸿蒙 ohos 全编译目标 —— 上游无 `ohosArm64` 变体 |

## 本地改动清单（相对上游 v2.8.0）

### 1. 删除 `diff/` 包（6 个文件）

`AnnotatedDiffText.kt` `JsonTreeDiff.kt` `JsonTreeDiffColors.kt` `JsonTreeDiffState.kt`
`JsonTreeDiffer.kt` `JsonTreeDifferState.kt`。

上游把「JSON 版本对比」与折叠树捆绑在同一制品里，`diff` 包的外部引用为零（唯一引用者在本包内），
删除后即消除对 `io.github.petertrr:kotlin-multiplatform-diff` 的传递依赖。

### 2. 删除 `util/JsonTreeElementExtensions.kt` 的 `toRenderString()`

唯一调用点是已删除的 `diff/JsonTreeDiffer.kt`，删除后无引用者。

### 3. `material3` → `material`（md2）

`JsonTree.kt` 只用到 `Text` / `Icon` / `LocalTextStyle` 三个符号，三者在 md2 与 md3 中签名一致
（`Text(AnnotatedString, …)`、`Icon(ImageVector, …)`、`LocalTextStyle` 均存在），
换用 md2 后消除 `org.jetbrains.compose.material3` 依赖，同时避免与本项目「桌面端不引 md3」的基线冲突。

### 4. 资源改由本模块 `composeResources` 提供

上游把展开箭头图标与子项计数文案打包在自己的 generated resources 里（包名
`jsontree.jsontree.generated.resources`），无法跨模块访问。本模块改为：

- `composeResources/drawable/jsontree_arrow_right.xml` —— 图标内容与上游逐字节相同；
- `composeResources/values/strings.xml` 与 `values-zh/strings.xml` —— 子项计数文案。
  上游此处用 plurals（`%1$d item` / `%1$d items`），中文无单复数差异，故统一为普通 string
  并以 `%1$d` 占位（**英文失去单复数区分**，是本改动唯一的行为差异）。

### 5. 新增：节点路径（`PathSegment` / `toJsonPath()`）

`JsonTreeElement` 各实现新增 `path: List<PathSegment>`，由 `toJsonTreeElement` 逐层累积；
`JsonTree()` 新增长按回传节点信息（回调签名见末尾"本地修改"清单）。

上游无此能力：原节点只带 `key` / `parentType`，折叠后脱离上下文无法回溯到根。

`toJsonPath()` 的拼接规则经本项目 `rjpath` 解析器逐字符实测标定（86 个键名用例 × 1/2 层深度，
覆盖 ASCII 可打印字符与中文）：

- 键名默认用点号写法（`$.a.b`）；出现以下字符之一时，该段改用方括号字面量：
  `.`（层级分隔符）、`*`（通配符）、`[` `]`（下标/切片语法）、`'` `"`（切换引号态，
  奇数个会吞掉后续分隔符）、`\`（进入转义态）、`$`（非首位时报错）；键名为空串时也走方括号写法
  （点号写法取不到值）；
- 方括号用哪种引号包裹：键名含 `"` 但不含 `'` 时用**单引号**，否则用**双引号**。
  rjpath 对两种引号都不做转义解码，只能挑键名里未出现的那种作边界；
- 其余字符（含空格、中文、`-` `,` `:` `?` `@` `(` `#` `/` `%` `&` `+` `=` `<` `>` `!` `~` `^` `|` `;`）
  两种写法实测等价，故走更短的点号写法。

实测结果：上述 86 例中 83 例能回查，**3 例无解**（键名同时含 `'` 与 `"`、键名恰为单个 `\`）——
上游解析器的字面量转义解码不完整（见 `docs/rjpath-single-quote-literals-issue.md`），
无引号组合可同时安全表达。这 3 例长按复制出的路径仍可读，但粘回规则里取不到值。

### 6. 修复 `util/` 渲染与缓存问题

`JsonObject` 建子节点逐项调 `entries.last()` 为 O(n²), 改按索引判尾;
`rememberCollapsableText`/`rememberPrimitiveText` 的 remember 缓存键缺
`type`/`childItemCount`/`isLastItem`/`parentType`, 折叠/展开后同槽位可能复用陈旧渲染文本, 已补全。

## 上游复活时如何合并

以 `v2.8.0` 为基线比对。第 1–3、6 项是删改，第 4 项是资源搬迁，第 5 项是新增能力。
升级上游时先看其是否已自带路径能力与 `onItemLongClick`：若已有，第 5 项应改为取上游实现；
第 1–4、6 项则需逐条重做（上游若已把 diff 拆成独立制品、或已改用 md2，可相应删除本地改动）。

## 本地修改 (偏离上游 v2.8.0)

- `JsonTree` 新增 `onItemLongClick: (JsonTreeItem) -> Unit` 与 `onItemContextMenu: (JsonTreeItem) -> Unit`:
  行级长按/右键回调, 长按不再被文本选择手势抢占; 回传同一 `JsonTreeItem` (path/key/value/quotedValue/窗口坐标),
  供宿主弹出复制菜单; 括号行不可点。
- `JsonTreeItem` 为本地新增公开数据类。
- 树列表的 `SelectionContainer` 已移除: 上游用它支撑鼠标拖选行文本, 但触屏/桌面长按会被文本选择手势消费,
  行级 `combinedClickable.onLongClick` 拿不到事件; 移除后长按回到行级手势, 鼠标拖选文本随之取消。
- `JsonTreeParser` 改用宽松解析实例 (lenient + 注释 + 尾逗号): 手写 JSON 常带这些宽松语法,
  与宿主全仓导入口径一致, 否则合法 (宽松) JSON 的树视图静默空白。
- 删除 `search/` 包 (`JsonTreeSearch.kt`/`SearchState.kt`) 与 `JsonTree` 的 `searchState` 参数:
  本仓零消费, 连带删除 `JsonTreeParser.expandAllItems()` (唯一调用方是搜索) 与
  `AnnotatedText` 两处 remember 的高亮分支。
- `util/JsonTreeElementExtensions.kt`: `JsonObject` 建子节点改按索引判尾, 消除逐项 `entries.last()` 的 O(n²);
  `util/AnnotatedText.kt`: `rememberCollapsableText`/`rememberPrimitiveText` 的 remember 键补全
  (`type`/`childItemCount`/`isLastItem`/`parentType`), 同槽位复用时不再渲染陈旧文本。
- 第 5 节 `toJsonPath()` 的标定能力不变; 长按回调签名以本清单为准。
