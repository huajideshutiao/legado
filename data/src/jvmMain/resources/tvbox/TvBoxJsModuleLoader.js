/*
 * TVBox JS spider 运行时引导: 极简 ESM→CJS 装载器。
 *
 * 为什么需要装载器: 本仓库 QuickJS 引擎只暴露 nativeEval (无 ES module 装载器),
 * 而 TVBox 生态的 JS spider (FongMi cat 系 / drpy2 系) 一律是 ES module:
 *   import { Crypto, jinja2, _ } from 'assets://js/lib/cat.js'; ... export function __jsEvalReturn(){...}
 * 故在 JS 侧实现 ESM→CJS 改写 + 极简 require, 让模块图能在 plain eval 上跑起来。
 *
 * async/await: 宿主已实现 Promise 泵送 —— 每次 JS 求值后 `QuickJsAsync.settleAndUnwrap`
 * 会循环 `QuickJsNative.nativePumpJobs` (JS_ExecutePendingJob) 把微任务链同步 settle,
 * 计时器也由同一 settle 循环触发 (见 QuickJsAsync/JsTimerManager)。故 spider 里的
 * async/await 与 Promise 链可正常工作。
 * 宿主 req/_http/http 仍走阻塞式 OkHttp 直接返回普通对象 (而非 Promise):
 * 这不是能力缺失, 而是刻意选择 —— spider 同步取数时不会让出线程给 JS 事件循环,
 * 语义最接近 FongMi 的 `async:false` 形态。
 *
 * `assets://<path>` 按 FongMi 语义解析为**宿主 App 自带 assets** (不是配置仓镜像路径);
 * 宿主未随包的依赖库由 TvBoxJsSpiderLoader 按需下载并缓存。
 *
 * 宿主门面一律字符串入参/出参 (JSON), 避免 JS 对象跨 JNI 边界的类型歧义。
 */

var __M = (function () {
    var modules = {};
    var cache = {};
    var base = '';
    var host = null;

    /**
     * JS 标识符: 放宽到 Unicode 字母 (生态样本含中文标识符, 如 `import 模板 from ...`)。
     * 非 ASCII 段用 `+` 量词 —— 中文标识符是多字符的, 单个字符匹配会在第二个字上断掉;
     * 该段遇到任何 ASCII 字符 (空格/引号/等号) 即停, 恰好等价于标识符边界。
     */
    var ID = '(?:[^\\x00-\\x7F]+|[_$A-Za-z][_$A-Za-z0-9]*)';
    var STR = '[\'"]([^\'"]*)[\'"]';

    function setHost(obj) {
        host = obj;
    }

    /**
     * 宿主调用: 单一 (方法名, JSON 数组字符串) 契约。
     * 全部走字符串, 避免 JS 对象跨 JNI 边界的类型歧义 (句柄/Map/JavaObject 不定)。
     */
    function jsonCall(method) {
        if (host == null) throw new Error('tvbox host not ready: ' + method);
        var args = [];
        for (var i = 1; i < arguments.length; i++) {
            var v = arguments[i];
            args.push(v == null ? '' : String(v));
        }
        return host.call(method, JSON.stringify(args));
    }

    function define(name, src) {
        modules[String(name)] = src;
        delete cache[String(name)];
        return String(name);
    }

    /** 宿主取源 (阻塞 IO: OkHttp 下载 / 本地文件), 由 TvBoxJsBridge 实现。 */
    function fetchSource(name) {
        return jsonCall('fetch', name, base);
    }

    /**
     * 相对路径按 base 归一 (无 URL 类依赖, 手撸 './' 与 '../')。
     * 生态里的裸路径 (如 'lib/drpy2.min.js') 同样是相对基准的, 故一律按 base 解析,
     * 只有带协议头的 (http/https/assets/file) 视为绝对。
     *
     * base 在 [require] 里按当前模块的 URL 切换 (模块内相对导入以模块自身为基准,
     * 对齐 FongMi `moduleNormalizeName` 的 UriUtil.resolve(baseModuleName, moduleName))。
     */
    function resolve(name) {
        var key = String(name);
        if (/^(https?|assets|file):/.test(key)) return key;
        var dir = base.indexOf('/') < 0 ? '' : base.substring(0, base.lastIndexOf('/'));
        var stack = dir.split('/');
        var parts = key.split('/');
        for (var i = 0; i < parts.length; i++) {
            var p = parts[i];
            if (p === '.' || p === '') continue;
            if (p === '..') stack.pop();
            else stack.push(p);
        }
        return stack.join('/');
    }

    function hash(s) {
        var h = 0;
        for (var i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) & 0x7fffffff;
        return h.toString(36);
    }

    /** '{a, b as c}' (import) → 'var a = src.a; var c = src.b;' */
    function bindNames(names, src) {
        var out = [];
        var items = names.split(',');
        for (var i = 0; i < items.length; i++) {
            var t = items[i].trim();
            if (!t) continue;
            var m = t.split(/\s+as\s+/);
            if (m.length === 2) out.push('var ' + m[1].trim() + ' = ' + src + '.' + m[0].trim() + ';');
            else out.push('var ' + t + ' = ' + src + '.' + t + ';');
        }
        return out.join('');
    }

    /** '{a, b as c}' (export) → 'exports.a = a; exports.c = b;' */
    function exportNames(names) {
        var out = [];
        var items = names.split(',');
        for (var i = 0; i < items.length; i++) {
            var t = items[i].trim();
            if (!t) continue;
            var m = t.split(/\s+as\s+/);
            if (m.length === 2) out.push('exports.' + m[1].trim() + ' = ' + m[0].trim() + ';');
            else out.push('exports.' + t + ' = ' + t + ';');
        }
        return out.join('');
    }

    /**
     * ESM → CJS 改写: 收集导出名统一回填 exports, 改写 import 为 require。
     *
     * 覆盖形态 (按生态实测样本): import {..} from 'x' / import ns from 'x' /
     * import * as ns / import 'x'; export function / class / const|let|var /
     * export {..} / export default (值|函数|类|匿名函数)。
     *
     * 两条外部约束 (违反即线上事故, 勿改):
     * - 改写出的模块调用必须走 `__M.require`, 不能写裸 `require`: 生态模块
     *   (drpy2 系) 自带同名 `function require`, 函数声明会遮蔽工厂的 require 参数,
     *   把装载调用劫持到模块自身的 require (其内部同步 request 触碰模块尾部
     *   才初始化的 const, 在模块顶层即炸 TDZ ReferenceError)。
     * - 匹配不得吞掉 import 语句尾部的 `;`: minified 文件把多个 import 挤在同一行,
     *   前导锚点 `(^|[;\\r\\n])` 依赖上一个语句留下的分号; 吃掉它会让后续 import
     *   保持 ESM 原文, 模块在 new Function 编译期直接语法错误。
     *
     * 已知限制: 字符串/正则字面量里出现 import|export 关键字会被误改写
     * (生态样本里未出现, 真出现时该模块会抛语法错并由宿主侧记为装载失败)。
     */
    function transform(src) {
        var body = String(src);
        var tail = [];

        // import {a, b as c} from 'x' —— 同样锚定语句起始 (见副作用导入的注释)。
        body = body.replace(
            new RegExp('(^|[;\\r\\n])\\s*import\\s*\\{([^}]*)\\}\\s*from\\s*' + STR + '\\s*', 'gm'),
            function (m, p1, names, spec) {
                return p1 + '\nvar __i' + hash(spec) + ' = __M.require("' + resolve(spec) + '");' +
                    bindNames(names, '__i' + hash(spec));
            });
        // import * as ns from 'x'
        body = body.replace(
            new RegExp('(^|[;\\r\\n])\\s*import\\s*\\*\\s*as\\s+(' + ID + ')\\s*from\\s*' + STR + '\\s*', 'gm'),
            function (m, p1, ns, spec) {
                return p1 + '\nvar ' + ns + ' = __M.require("' + resolve(spec) + '");';
            });
        // import ns from 'x' (default 导出); minified 样本常写成 `import 模板 from"..."`
        // (标识符与 from 之间无空格), 故用 \\s* 而非 \\s+。
        body = body.replace(
            new RegExp('(^|[;\\r\\n])\\s*import\\s+(' + ID + ')\\s*from\\s*' + STR + '\\s*', 'gm'),
            function (m, p1, ns, spec) {
                return p1 + '\nvar ' + ns + ' = __M.require("' + resolve(spec) + '").__defaultOf();';
            });
        // import 'x' (副作用导入)。
        // 必须锚定语句起始位置: cat.js 内嵌的模板/PEG 解析器含 `skipSymbol("import")`
        // 这类字符串字面量, 无锚点的 `import\s*['"]...` 会把字面量吃掉并破坏括号平衡。
        // 前导锚点取行首或 `;`/换行之后的空白 (minified 单行多 import 也满足);
        // 匹配不吞尾部 `;`, 它是同行后续 import 的锚点 (见函数头部的外部约束)。
        body = body.replace(
            new RegExp('(^|[;\\r\\n])\\s*import\\s*' + STR + '\\s*', 'gm'),
            function (m, p1, spec) {
                return p1 + '\n__M.require("' + resolve(spec) + '");';
            });

        // export default function f / async function f
        body = body.replace(
            new RegExp('export\\s+default\\s+(?:async\\s+)?function\\s+(' + ID + ')', 'g'),
            function (m, name) {
                tail.push('exports.default = ' + name + ';');
                return '\nfunction ' + name;
            });
        // export default function( (匿名)
        body = body.replace(
            new RegExp('export\\s+default\\s+(?:async\\s+)?function\\s*\\(', 'g'),
            '\nvar __defaultExport = function(');
        // export default class X
        body = body.replace(
            new RegExp('export\\s+default\\s+class\\s+(' + ID + ')', 'g'),
            function (m, name) {
                tail.push('exports.default = ' + name + ';');
                return '\nclass ' + name;
            });
        // export default {..} —— minified 文件最常见的收尾形态 (`export default{a:a,b:b}`),
        // 首个字符就是 '{', 故单独一条规则: 从 default 后一路吃到行尾 (含无分号收尾)。
        body = body.replace(
            new RegExp('export\\s+default\\s*(\\{.*)\\\s*;?\\s*$', 'm'),
            '\nvar __defaultExport = $1;');
        // export default <expr>; —— 表达式内不含 '=' (生态样本均为对象/标识符);
        // 末尾分号可有可无。
        body = body.replace(
            new RegExp('export\\s+default\\s+([^=;{][^;]*);', 'g'),
            '\nvar __defaultExport = $1;');
        body = body.replace(
            new RegExp('export\\s+default\\s+([^=;{][^;]*)\\s*$', 'm'),
            '\nvar __defaultExport = $1;');

        // export [async] function[*] f
        body = body.replace(
            new RegExp('export\\s+(async\\s+)?function\\s*\\*?\\s*(' + ID + ')', 'g'),
            function (m, asy, name) {
                tail.push('exports.' + name + ' = ' + name + ';');
                return '\n' + (asy || '') + 'function ' + name;
            });
        // export class X
        body = body.replace(
            new RegExp('export\\s+class\\s+(' + ID + ')', 'g'),
            function (m, name) {
                tail.push('exports.' + name + ' = ' + name + ';');
                return '\nclass ' + name;
            });
        // export const/let/var x
        body = body.replace(
            new RegExp('export\\s+(const|let|var)\\s+(' + ID + ')', 'g'),
            function (m, kind, name) {
                tail.push('exports.' + name + ' = ' + name + ';');
                return '\n' + kind + ' ' + name;
            });
        // export {a, b as c}
        body = body.replace(
            new RegExp('export\\s*\\{([^}]*)\\}\\s*;?', 'g'),
            function (m, names) {
                tail.push(exportNames(names));
                return '\n';
            });

        tail.push('if (typeof __defaultExport !== "undefined") exports.default = __defaultExport;');
        return body + '\n;' + tail.join('') + '\n';
    }

    /**
     * 模块加载: 取源 → 改写 → CJS 包装执行 → 回填导出。
     * 先把 exports 空壳进 cache, 循环依赖时拿到部分导出而非死循环。
     *
     * 外部约束 (违反即子模块相对导入解析错位, 勿改): 本模块的改写与执行期间,
     * base 必须切到模块自身的 URL。模块内的 `import "./x.js"` 是相对**模块所在目录**
     * 的 (FongMi `moduleNormalizeName` 语义), 用全局 base (配置 URL 目录) 会解析成
     * 上一级目录而 404 —— drpy2 系站点 (`./FTY/drpy2.min.js` 内 `./node-rsa.js`)
     * 正是靠这一条才拿得到同目录依赖。
     */
    function require(name) {
        var key = resolve(name);
        if (cache[key]) return cache[key];
        var src = modules[key];
        if (src == null) src = fetchSource(key);
        if (src == null || src === '') throw new Error('tvbox js module not found: ' + key);
        var exports = {};
        exports.__defaultOf = defaultOf;
        cache[key] = exports;
        var prev = base;
        base = key;
        try {
            var body = transform(src);
            var factory = new Function('exports', 'require', 'module', '__M',
                '(function(){' + body + '\n})()');
            factory(exports, require, { exports: exports }, api);
        } finally {
            base = prev;
        }
        return cache[key];
    }

    /** default 导出解析: 有 default 取 default, 否则整个命名空间 (对齐 interop 习惯)。 */
    function defaultOf() {
        return this && this.default !== undefined ? this.default : this;
    }

    var api = {
        setHost: setHost,
        define: define,
        require: require,
        resolve: resolve,
        jsonCall: jsonCall,
        transform: transform,
        setBase: function (b) { base = String(b || ''); },
        getBase: function () { return base; },
        modules: function () { return modules; },
        cache: function () { return cache; }
    };
    return api;
})();
