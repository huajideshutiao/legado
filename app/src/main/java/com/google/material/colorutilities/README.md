# material-color-utilities vendor 子集

Google 官方取色算法源码, 来源 material-foundation/material-color-utilities
(main 分支 bd811e4ca2ab94c4ce3cadc89c19b1820b9704b3, 2026-10-24 拉取),
Apache License 2.0 (同目录 LICENSE)。

## 为何 vendor 而非依赖

官方未发布该算法的 Maven 产物; Maven Central 的 com.materialkolor:material-color-utilities
各版本的 JVM/Android 变体均无产物文件 (Gradle module metadata 里 files 为空, 实测验证),
不可用。随 com.google.android.material:material 整库引入则拖入全套视图系传递依赖
(约束布局/列表/翻页/过渡动画等), 而本仓库只消费其中 3 个算法入口。

## 内容

按 import 闭包从官方 java/ 目录提取的 26 个文件, 包名加
`com.google.material.colorutilities.` 前缀, 其余逐行原样 (含文件头 Apache 许可声明);
剥除了 error_prone 注解 (@Var/@CanIgnoreReturnValue/@CheckReturnValue, 纯静态提示,
其 artifact 随 material 移除后不在依赖内)。消费入口:
`io.legado.app.lib.theme.MonetColorExtract` 用 Hct / SchemeContent / MaterialDynamicColors。

## 更新方式

从官方仓库 java/ 目录取新版本, 重跑同款闭包 (roots: Hct, SchemeContent,
MaterialDynamicColors; 源码中出现的同项目类型名递归收录), 保持本 README 与
hash 同步更新。
