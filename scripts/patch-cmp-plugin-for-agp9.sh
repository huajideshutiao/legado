#!/usr/bin/env bash
# 给 CPF fork 的 compose-gradle-plugin 打字节码补丁 (仅 ohos 模式需要)。
#
# 根因: fork 的 AndroidResourcesKt 在配置 compose 资源时调用
#   KotlinMultiplatformAndroidComponentsExtension.onVariant(Function1)
# AGP 9.0.0-alpha01 起该 API 已移除 (官方 CMP 1.11.1 按 pluginVersion 分支改用基接口的
# onVariants, 见 compose-multiplatform PR #5385; fork 未合入)。故把该调用点改写为
# onVariants(selector(), block), 与官方 >= 8.10 分支一致。
#
# 【为何需要本脚本】: 鸿蒙与 Android 共用同一份共享源码, 配置期躲不开 AGP 插件; 而 CMP 的
# 资源接线挂在 AGP 插件 id 的回调上 (onAgpApplied), 插件一 apply 就必然执行。ohos 模式
# 只把 Kotlin/CMP 切到 fork, AGP 留在主线, 形成「fork CMP × 主线 AGP」的错配组合。
#
# 【守卫集中在本脚本】:
#   1. 工具链与 fork jar 必须可解析 (否则直接退出, 不产出半成品);
#   2. 版本配对: fork 版本或 AGP 版本与上次产出时不一致 -> 拒绝并提示, 不静默沿用旧补丁;
#   3. 字节码结构: 改写的类/方法/描述符必须命中且只命中 1 处 -> 否则退出
#      (fork 升级改了实现时当场报错, 而不是静默产出一个无效 jar)。
#
# 产物 (均不入版本库, .gitignore 已忽略 build-logic/libs/):
#   build-logic/libs/compose-gradle-plugin-<fork 版本>-patched.jar
#   build-logic/libs/cmp-plugin-patch.properties  (版本配对清单)
# build-logic/build.gradle.kts 在 ohos 模式下消费该 jar; 缺失或版本不匹配时构建失败。
set -eu

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$REPO_ROOT/build-logic/libs"
WORK="$REPO_ROOT/build/.cmp-plugin-patch"

# 工具链: 优先 JAVA_HOME, 否则取 gradle 缓存的 JDK 21
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
    JAVAC="$JAVA_HOME/bin/javac"
else
    JDK="/c/Users/$USERNAME/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2"
    [ -d "$JDK" ] || JDK="$(ls -d "$HOME"/.gradle/jdks/eclipse_adoptium-21-* 2>/dev/null | head -1)"
    JAVA="$JDK/bin/java"
    JAVAC="$JDK/bin/javac"
fi
[ -x "$JAVA" ] || { echo "找不到 JDK 21: $JAVA" >&2; exit 1; }

# fork 版本从版本目录读取 (单数据源, 禁止硬编码)
FORK_VERSION="$(grep -E '^composeMultiplatform-ohos\s*=' "$REPO_ROOT/gradle/libs.versions.toml" \
    | sed -E 's/.*"(.*)".*/\1/')"
[ -n "$FORK_VERSION" ] || { echo "无法从 libs.versions.toml 读取 composeMultiplatform-ohos" >&2; exit 1; }

GCACHE="$HOME/.gradle/caches/modules-2/files-2.1"
GRADLE_LIB="$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-*-bin/*/gradle-*/lib 2>/dev/null | sort -V | tail -1)"
# 必须取核心 asm jar: 同目录还有 asm-tree/asm-util/asm-analysis/asm-commons,
# 它们不含 ClassReader/ClassWriter
ASM="$(ls "$GRADLE_LIB"/asm-[0-9]*.jar 2>/dev/null | sort -V | tail -1)"
[ -f "$ASM" ] || { echo "找不到 ASM jar (Gradle lib 内): $GRADLE_LIB" >&2; exit 1; }

FORK_JAR="$(find "$GCACHE/org.jetbrains.compose/compose-gradle-plugin/$FORK_VERSION" -name "*.jar" 2>/dev/null | grep -v sources | head -1)"
[ -n "$FORK_JAR" ] || { echo "找不到 fork compose-gradle-plugin $FORK_VERSION (先跑一次 ohos 构建以拉取依赖)" >&2; exit 1; }

AGP_VERSION="$(grep -E '^agp\s*=' "$REPO_ROOT/gradle/libs.versions.toml" | sed -E 's/.*"(.*)".*/\1/')"
[ -n "$AGP_VERSION" ] || { echo "无法从 libs.versions.toml 读取 agp" >&2; exit 1; }

# 版本配对守卫: 补丁只在「同一对 (fork, AGP) 版本」上验证过。两者任一变化就拒绝复用,
# 必须由人重新确认 fork 是否已合入官方修复、或改写点是否仍存在。
MANIFEST="$OUT_DIR/cmp-plugin-patch.properties"
OUT_JAR="$OUT_DIR/compose-gradle-plugin-$FORK_VERSION-patched.jar"
if [ -f "$MANIFEST" ] && [ -f "$OUT_JAR" ]; then
    prev_fork="$(sed -nE 's/^composeForkVersion=(.*)$/\1/p' "$MANIFEST")"
    prev_agp="$(sed -nE 's/^agpVersion=(.*)$/\1/p' "$MANIFEST")"
    if [ "$prev_fork" != "$FORK_VERSION" ] || [ "$prev_agp" != "$AGP_VERSION" ]; then
        echo "[cmp-plugin-patch] 版本变化: fork $prev_fork -> $FORK_VERSION, agp $prev_agp -> $AGP_VERSION" >&2
        echo "[cmp-plugin-patch] 将重新生成补丁; 请确认 fork 是否已自行合入官方修复 (PR #5385)。" >&2
    fi
fi

mkdir -p "$OUT_DIR" "$WORK/src" "$WORK/classes" "$WORK/out"

cat > "$WORK/src/PatchAndroidResources.java" <<'JAVA'
import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.jar.*;

/**
 * 把 AndroidResourcesKt 里对
 *   KotlinMultiplatformAndroidComponentsExtension.onVariant(Function1)
 * 的调用改写为
 *   KotlinMultiplatformAndroidComponentsExtension.onVariants(selector(), Function1)
 *
 * 栈变换 (调用点原栈为 [receiver, block]):
 *   ASTORE 4                      -> [receiver]
 *   DUP                           -> [receiver, receiver]
 *   INVOKEINTERFACE selector()    -> [receiver, selector]
 *   ALOAD 4                       -> [receiver, selector, block]
 *   INVOKEINTERFACE onVariants(VariantSelector, Function1)
 *
 * selector() 即 onVariants 的 Kotlin 默认值来源 (AndroidComponentsExtension.kt),
 * 显式取它与走默认参数等价; 目标方法为直线代码, 故只需重算 maxStack。
 */
public class PatchAndroidResources {
    static final String OWNER = "com/android/build/api/variant/KotlinMultiplatformAndroidComponentsExtension";
    static final String TARGET_CLASS = "org/jetbrains/compose/resources/AndroidResourcesKt.class";
    static final String OLD_NAME = "onVariant";
    static final String OLD_DESC = "(Lkotlin/jvm/functions/Function1;)V";
    static final String NEW_NAME = "onVariants";
    static final String NEW_DESC = "(Lcom/android/build/api/variant/VariantSelector;Lkotlin/jvm/functions/Function1;)V";
    static final String SELECTOR_DESC = "()Lcom/android/build/api/variant/VariantSelector;";

    public static void main(String[] args) throws Exception {
        Path in = Paths.get(args[0]);
        Path outJar = Paths.get(args[1]);
        Files.createDirectories(outJar.getParent());

        int patched = 0;
        try (JarFile jar = new JarFile(in.toFile());
             JarOutputStream jos = new JarOutputStream(Files.newOutputStream(outJar))) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String n = e.getName();
                // 替换内容会让原签名失效, 签名条目直接丢弃
                if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".DSA")
                        || n.endsWith(".RSA") || n.endsWith(".EC"))) {
                    continue;
                }
                byte[] data = jar.getInputStream(e).readAllBytes();
                if (n.equals(TARGET_CLASS)) {
                    data = patch(data);
                    patched++;
                }
                jos.putNextEntry(new JarEntry(n));
                jos.write(data);
                jos.closeEntry();
            }
        }
        if (patched != 1) {
            throw new IllegalStateException("期望改写 1 个类, 实际 " + patched + " —— fork 版本可能已变, 请复核");
        }
        System.out.println("[cmp-plugin-patch] 已改写 " + patched + " 个类 -> " + outJar);
    }

    static byte[] patch(byte[] cls) {
        ClassReader cr = new ClassReader(cls);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean itf) {
                        if (owner.equals(OWNER) && mName.equals(OLD_NAME) && mDesc.equals(OLD_DESC)) {
                            super.visitVarInsn(Opcodes.ASTORE, 4);
                            super.visitInsn(Opcodes.DUP);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, OWNER, "selector", SELECTOR_DESC, true);
                            super.visitVarInsn(Opcodes.ALOAD, 4);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE, OWNER, NEW_NAME, NEW_DESC, true);
                        } else {
                            super.visitMethodInsn(opcode, owner, mName, mDesc, itf);
                        }
                    }
                };
            }
        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }
}
JAVA

"$JAVAC" -cp "$ASM" -d "$WORK/classes" "$WORK/src/PatchAndroidResources.java"
rm -f "$OUT_JAR"
"$JAVA" -cp "$WORK/classes:$ASM" PatchAndroidResources "$FORK_JAR" "$OUT_JAR"

# 版本配对清单: build-logic 按 jar 名绑定 fork 版本, 本清单供人工/后续脚本核对配对。
cat > "$MANIFEST" <<EOF
# 由 scripts/patch-cmp-plugin-for-agp9.sh 生成, 勿手改
composeForkVersion=$FORK_VERSION
agpVersion=$AGP_VERSION
EOF
echo "[cmp-plugin-patch] 清单: fork=$FORK_VERSION agp=$AGP_VERSION"
