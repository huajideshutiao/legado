# vendor 溯源

## GB18030.kt

- 来源仓库: https://github.com/fleeksoft/fleeksoft-io (fleeksoft charset 项目已迁入该仓库)
- 来源路径: `charset-ext-big/src@nonJvm/com/fleeksoft/charset/cs/ext/GB18030.kt`
- 固定提交: `6e7b17ded5d4fa912236f3d4c80100a93d5e5223`
- SHA-256: `b81c8bace986c3a6c2f27a569dc3f4788f5213151b42ca23a8946ef13df6cbaa`
  (与上游该提交 raw 文件逐字节一致, 本目录文件未做任何修改)
- 许可证: Apache-2.0 (该文件自身无许可证头, 以仓库 LICENSE.md 为准; 其中编解码算法与
  映射表移植自 OpenJDK `sun.nio.cs.ext.GB18030`, GPL2 + Classpath exception)

### 使用约束

1. `IS_2000 = false` → GB18030-2022 语义; 本仓 GB18030 别名只接受 `GB18030-2022`,
   不接受 `GB18030-2000` (两个版本映射不同, 不可混称)。
2. 文件内 `com.fleeksoft.io.getInt` 是 io-core 0.0.8 的单字节 `get().toInt()` 扩展
   (逐字节拼码点), 不是 java.nio 的 4 字节 getInt, 不得"修复"。
3. `charset-ext-big` 尚无 Maven 0.0.8 发布, 故以固定提交源码 vendor; 禁止手抄/改动
   映射表, 升级时以上游新提交重新校验 SHA 后整体替换。

## UTF-32 编解码

`src/nativeMain/kotlin/com/legado/charset/Utf32Coder.kt` 为
`com.fleeksoft.charset:charset:0.0.8` sources 中
`nonJvmMain/com/fleeksoft/charset/cs/utf/UTF_32Coder.kt` 的承载副本: 换包 `com.legado.charset`
并将对象改名 `Utf32Coder` (避免与依赖 klib 同包重类), 算法唯一改动是解码对代理区/超范围
码点返回 malformed(4), 其余逐行同上游; 不在本目录重复存放原版副本, 原版以 Maven 构件坐标
+ 版本溯源。
