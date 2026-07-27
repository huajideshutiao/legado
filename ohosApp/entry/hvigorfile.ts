// entry 模块级 hvigor 构建脚本
// 加载 @ohos/hvigor-ohos-plugin 提供的 hapTasks (HAP 打包任务)
import { hapTasks } from '@ohos/hvigor-ohos-plugin';

export default {
  system: hapTasks,
  plugins: []
}
