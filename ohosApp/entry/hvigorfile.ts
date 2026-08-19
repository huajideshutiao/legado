// entry 模块级 hvigor 构建脚本
// 加载 @ohos/hvigor-ohos-plugin 提供的 hapTasks (HAP 打包任务)
import { hapTasks } from '@ohos/hvigor-ohos-plugin';
import type { HvigorNode, HvigorPlugin } from '@ohos/hvigor';

const childProcess: typeof import('child_process') = require('child_process');
const util: typeof import('util') = require('util');
const path: typeof import('path') = require('path');
const execFileAsync = util.promisify(childProcess.execFile);
const projectRoot = path.resolve(__dirname, '..', '..');
const gradleWrapper = path.resolve(projectRoot, 'gradlew.bat');

function legadoNativeLibrariesPlugin(): HvigorPlugin {
  return {
    pluginId: 'legado-native-libraries',
    apply(pluginContext: HvigorNode) {
      pluginContext.registerTask({
        name: 'stageLegadoNativeLibraries',
        run: async () => {
          // Windows 上 node 的 execFile 不能直接执行 .bat/.cmd (EINVAL/ENOENT),
          // 统一经 powershell.exe 的调用运算符 & 执行 gradlew.bat (cmd/powershell 均可, 选 ps1 以兼容含空格路径)。
          const gradleArgs =
            'stageOhosNativeLibraries -PenableOhosTarget=true -PrendererBackend=fusion-renderer';
          const { stdout, stderr } = await execFileAsync(
            'powershell.exe',
            [
              '-NoProfile',
              '-ExecutionPolicy',
              'Bypass',
              '-Command',
              `& '${gradleWrapper}' ${gradleArgs}`
            ],
            {
              cwd: projectRoot,
              env: process.env,
              windowsHide: true,
              maxBuffer: 16 * 1024 * 1024
            }
          );
          if (stdout) {
            console.log(stdout);
          }
          if (stderr) {
            console.error(stderr);
          }
        },
        postDependencies: ['default@ConfigureCmake']
      });
    }
  };
}

export default {
  system: hapTasks,
  plugins: [legadoNativeLibrariesPlugin()]
}
