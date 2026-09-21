# 内嵌第三方库来源说明（vendored）

本目录的源码不是本项目编写，是逐文件复制进来的第三方库副本，许可为 **MIT**，
许可证原文见同目录 `LICENSE`（与上游逐字节相同，1065 字节）。

| 项 | 值 |
|---|---|
| 上游仓库 | https://github.com/jershell/rjpath |
| 对应 commit | `d38d43ac4086fcf2a4e83e514d5f133c975c6cb5`（2025-04-09，"Initial commit with all features"） |
| 复制的文件 | `RJPath.kt` `RJPathOptions.kt` `RegexMatchMode.kt` `SelectorParser.kt` `expressions.kt` `functions.kt` `selectors.kt` `types.kt` |
| 为何内嵌而非依赖坐标 | 上游**从未向 Maven Central 发布过任何制品**（实测 `search.maven.org` 命中 0），无坐标可引；且需支持 Android / 桌面 JVM / iOS native / 鸿蒙 ohos 全编译目标 |

## 本地改动清单（相对上游 commit `d38d43a`）

### 1. `RJPath.kt`：新增 `read(element: JsonElement)`

兼容迁移前 jayway json-path 的读值口径：无匹配返回 null、单值不套列表、路径直指数组时展开为 List。
其余 7 个文件与上游逐字节相同（仅换行符 CRLF/LF 差异）。

### 2. `SelectorParser.kt` / `expressions.kt`：修复单引号字面量不设防

`SelectorParser` 的类注释自称 "JSONPath expression parser according to RFC9535"。
RFC 9535 §2.5 的 `string-literal` 产生式是：

```
string-literal = %x22 *double-quoted %x22 /   ; "string"
                 %x27 *single-quoted  %x27    ; 'string'
```

即**单引号与双引号都是合法字面量**。原实现只把双引号纳入引号态，单引号串完全不设防，
由此产生下列对合法写形的错解析：

| 写法 | 修复前 | 修复后 |
|---|---|---|
| `$['a:b']` | 被当作数组切片 | 成员名 `a:b` |
| `$['a,b']` | 被拆成两个键且残留引号（`'a`、`b'`） | 成员名 `a,b` |
| `$['a]b']` | 在串内 `]` 处提前截断成两个错 token | 成员名 `a]b` |
| `$[?(@.t=='x||y')]` | 从串内 `\|\|` 切开，过滤条件变成"`=='x` 或 `y'`"，语义反转 | 单一比较式 |
| `$[?(@.a=='x&&y')]` | 同上（`&&`） | 单一比较式 |
| `$[?(@.t=='a,b')]` | 串内逗号被当参数分隔符 | 不切 |
| `$.a[` / `$[` | `substring(1, 0)` 抛越界异常 | 抛带原文的 `IllegalArgumentException`（上层按规则错误处理） |

另外 `expressions.kt` 的第二份 `parseFunctionArgs` 把两种引号**共用同一个 `inQuotes` 标志**，
使 `@.t,"it's,x"` 里双引号串内的撇号把状态翻反、后面的真逗号不再切分；已改为两个互斥标志。
第一份 `parseFunctionArgs` 则完全没有单引号分支，`@.t,'a,b'` 被切成 3 段；已补齐。

本项目的修复保留"两种引号都接受"，不做收紧——存量书源规则里双引号写法广泛存在，
按某一派方言收紧会让这些规则突然取不到数据。

上游已停更（该 commit 之后无任何提交）且无发布通道，故上述修改为永久本地补丁。

## 上游复活时如何合并

以 commit `d38d43a` 为基线比对：第 1、2 项均为增量修改。
`SelectorParser.kt` / `expressions.kt` 若上游出现新版本，应按上表逐条核对上游是否已自行修复，
再决定取上游还是保留补丁，**不要整文件覆盖**。

已知仍未修（记录备查）：
- 单引号串内的反斜杠转义序列（`\'`、`\n`、`\uXXXX`）不参与解码，键名里带这些序列时取不到值；
- 过滤器不支持 `=~`、正则风格的成员名以及嵌套路径谓词（如 `[a[b=1]]` 整体不作数）。
