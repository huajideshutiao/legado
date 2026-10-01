# EPUB4KMP for OHOS

This module recompiles Maven source archives for the CPF `ohosArm64` target:

- epub4kmp-core 0.3.0 (Apache-2.0): https://github.com/Darkrock-Studios/epub4kmp
- XMLUtil core 0.91.3 (Apache-2.0): https://github.com/pdvrieze/xmlutil
- kmp-zip / kmp-zip-okio 0.12.1 (MPL-2.0): https://github.com/henrik242/kmp-zip

Upstream archives remain unchanged. Build-generated adaptations bind the SDK's
zlib through cinterop and use Instant's UTC ISO date instead of kotlinx-datetime.
The POSIX and pure Kotlin crypto implementations come from kmp-zip's Linux sources.
No Apple or JVM binaries are used on OHOS. The module is substituted only for OHOS
configurations; other targets continue to use the published EPUB4KMP dependency.
