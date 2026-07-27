// ohosApp 工程级 hvigor 构建脚本
// 鸿蒙 hvigor 用 TypeScript 配置, 类似 Android 的 settings.gradle
// 启用hvigorfile.ts后, 鸿蒙工程能力从hvigor-config.json5读取
import { appTasks } from '@ohos/hvigor-ohos-plugin';

export default {
  system: appTasks,
  plugins: []
}
