// ohosApp 工程级 hvigor 构建脚本
// 鸿蒙 hvigor 用 TypeScript 配置, 类似 Android 的 settings.gradle
// 启用hvigorfile.ts后, 鸿蒙工程能力从hvigor-config.json5读取
//
// 包名/版本号与安卓端对齐 (app/build.gradle.kts):
// - bundleName  = shutiao.reader + 构建类型后缀 (debug → shutiao.reader.debug, release → shutiao.reader.release)
// - versionCode = 10000 + git 提交数
// - versionName = "3." + 构建时刻 yy.MMddHH (GMT+8 墙上时钟)
// 经 hvigor 官方 overrides.appOpt 在配置阶段覆盖 app.json5 的静态值,
// build-profile 生成与 pack-info 打包两个下游消费者都读这份覆盖。
// 注意: 打 release 包请显式传 -p buildMode=release (DevEco 打包/CI), 否则按 debug 兜底。
import { appTasks } from '@ohos/hvigor-ohos-plugin';
import { hvigorCore } from '@ohos/hvigor';
import { execSync } from 'child_process';

function gitCommits(): number {
  try {
    return parseInt(execSync('git rev-list --count HEAD').toString().trim(), 10);
  } catch {
    return 0;
  }
}

function pad(n: number): string {
  return n.toString().padStart(2, '0');
}

// 构建模式: 显式 -p buildMode=debug/release; 未传时按 debug 兜底 (IDE assembleHap 默认 debug)。
function buildMode(): string {
  return hvigorCore.getExtraConfig().get('buildMode') || 'debug';
}

// GMT+8 墙上时钟 (与安卓 releaseTime() 的 ZoneId "GMT+8" 口径一致; 与机器本地时区无关)。
function gmt8(): Date {
  return new Date(Date.now() + 8 * 3600 * 1000);
}

const commits = gitCommits();
const mode = buildMode();
const now = gmt8();
const bundleName = 'shutiao.reader' + (mode === 'release' ? '.release' : '.debug');
const versionName = '3.' + pad(now.getUTCFullYear() % 100) + pad(now.getUTCMonth() + 1) +
  pad(now.getUTCDate()) + pad(now.getUTCHours());

export default {
  system: appTasks,
  plugins: [],
  config: {
    ohos: {
      overrides: {
        appOpt: {
          bundleName: bundleName,
          versionCode: 10000 + commits,
          versionName: versionName,
        },
      },
    },
  },
};
