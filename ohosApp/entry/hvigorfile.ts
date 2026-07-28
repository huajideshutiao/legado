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
          const { stdout, stderr } = await execFileAsync(
            gradleWrapper,
            [
              'stageOhosNativeLibraries',
              '-PenableOhosTarget=true',
              `-PdevecoSdkHome=${process.env.DEVECO_SDK_HOME ?? ''}`
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
