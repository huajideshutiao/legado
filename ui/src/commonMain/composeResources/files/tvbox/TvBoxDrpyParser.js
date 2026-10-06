/*
 * drpy2 宿主契约全局: pdfh/pdfa/pd。
 *
 * 外部约束 (违反即 drpy2 站点无法装载, 勿删):
 * - drpy2.min.js 顶层有 `const defaultParser={pdfh:pdfh,pdfa:pdfa,pd:pd}`
 *   (dr_py drpy2.js 源码注释"内置 pdfh,pdfa,pd"), 三函数必须是装载前已存在的
 *   宿主全局, 缺失在模块求值期即抛 ReferenceError (gao 配置实测)。
 * - 实现取自 drpy-node libs_drpy/htmlParser.js (海阔视界解析语法), 类名/方法
 *   名与解析语义原样保留; 差异仅: urljoin 换宿主 joinUrl, jsonpath 面 (pj*)
 *   不在 defaultParser 契约内未搬, cheerio 经 __M.require 惰性取源
 *   (assets://js/lib/cheerio.min.js, 与 drpy2 的 import 共用同一模块缓存)。
 */

var __drpyCheerio = null;

function __drpyCheerioLib() {
    if (__drpyCheerio === null) {
        __drpyCheerio = __M.require('assets://js/lib/cheerio.min.js');
    }
    return __drpyCheerio;
}

// 解析缓存开关
const PARSE_CACHE = true; // 解析缓存
// 不自动加eq下标索引的选择器
const NOADD_INDEX = ':eq|:lt|:gt|:first|:last|:not|:even|:odd|:has|:contains|:matches|:empty|^body$|^#'; // 不自动加eq下标索引
// 需要自动urljoin的属性
const URLJOIN_ATTR = '(url|src|href|-original|-src|-play|-url|style)$|^(data-|url-|src-)'; // 需要自动urljoin的属性
// 过滤特殊链接,不走urlJoin
const SPECIAL_URL = '^(ftp|magnet|thunder|ws):'; // 过滤特殊链接,不走urlJoin

/**
 * HTML解析器类
 * 提供类似海阔视界的HTML解析语法
 */
class Jsoup {
    /**
     * 构造函数
     * @param {string} MY_URL 基础URL
     */
    constructor(MY_URL = '') {
        this.MY_URL = MY_URL;
        this.pdfh_html = '';
        this.pdfa_html = '';
        this.pdfh_doc = null;
        this.pdfa_doc = null;
    }

    /**
     * 正则测试
     * @param {string} text 正则表达式
     * @param {string} string 测试字符串
     * @returns {boolean} 是否匹配
     */
    test(text, string) {
        const searchObj = new RegExp(text, 'mi').exec(string);
        return searchObj ? true : false;
    }

    /**
     * 检查字符串是否包含指定内容
     * @param {string} text 源字符串
     * @param {string} match 匹配内容
     * @returns {boolean} 是否包含
     */
    contains(text, match) {
        return text.indexOf(match) !== -1;
    }

    /**
     * 将海阔视界解析语法转换为jQuery选择器
     * @param {string} parse 解析规则
     * @param {boolean} first 是否只取第一个
     * @returns {string} 转换后的选择器
     */
    parseHikerToJq(parse, first = false) {
        if (this.contains(parse, '&&')) {
            const parses = parse.split('&&');
            const new_parses = [];
            for (let i = 0; i < parses.length; i++) {
                const ps_list = parses[i].split(' ');
                const ps = ps_list[ps_list.length - 1];
                if (!this.test(NOADD_INDEX, ps)) {
                    if (!first && i >= parses.length - 1) {
                        new_parses.push(parses[i]);
                    } else {
                        new_parses.push(`${parses[i]}:eq(0)`);
                    }
                } else {
                    new_parses.push(parses[i]);
                }
            }
            parse = new_parses.join(' ');
        } else {
            const ps_list = parse.split(' ');
            const ps = ps_list[ps_list.length - 1];
            if (!this.test(NOADD_INDEX, ps) && first) {
                parse = `${parse}:eq(0)`;
            }
        }
        return parse;
    }

    /**
     * 获取解析信息
     * @param {string} nparse 解析规则
     * @returns {Object} 解析信息对象
     */
    getParseInfo(nparse) {
        let excludes = [];
        let nparse_index = 0;
        let nparse_rule = nparse;

        if (this.contains(nparse, ':eq')) {
            nparse_rule = nparse.split(':eq')[0];
            let nparse_pos = nparse.split(':eq')[1];
            if (this.contains(nparse_rule, '--')) {
                excludes = nparse_rule.split('--').slice(1);
                nparse_rule = nparse_rule.split('--')[0];
            } else if (this.contains(nparse_pos, '--')) {
                excludes = nparse_pos.split('--').slice(1);
                nparse_pos = nparse_pos.split('--')[0];
            }
            try {
                nparse_index = parseInt(nparse_pos.split('(')[1].split(')')[0]);
            } catch {
            }
        } else if (this.contains(nparse, '--')) {
            nparse_rule = nparse.split('--')[0];
            excludes = nparse.split('--').slice(1);
        }

        return {nparse_rule, nparse_index, excludes};
    }

    /**
     * 重新排序相邻的:gt和:lt选择器
     * @param {string} selector 选择器
     * @returns {string} 重排后的选择器
     */
    reorderAdjacentLtAndGt(selector) {
        const adjacentPattern = /:gt\((\d+)\):lt\((\d+)\)/;
        let match;
        while ((match = adjacentPattern.exec(selector)) !== null) {
            const replacement = `:lt(${match[2]}):gt(${match[1]})`;
            selector = selector.substring(0, match.index) + replacement + selector.substring(match.index + match[0].length);
            adjacentPattern.lastIndex = match.index;
        }
        return selector;
    }

    /**
     * 解析单个规则
     * @param {Object} doc cheerio文档对象
     * @param {string} nparse 解析规则
     * @param {Object} ret 上一步结果
     * @returns {Object} 解析结果
     */
    parseOneRule(doc, nparse, ret) {
        let {nparse_rule, nparse_index, excludes} = this.getParseInfo(nparse);
        nparse_rule = this.reorderAdjacentLtAndGt(nparse_rule);
        if (!ret) ret = doc(nparse_rule);
        else ret = ret.find(nparse_rule);

        if (this.contains(nparse, ':eq')) ret = ret.eq(nparse_index);

        if (excludes.length > 0 && ret) {
            ret = ret.clone();
            for (let exclude of excludes) {
                ret.find(exclude).remove();
            }
        }

        return ret;
    }

    /**
     * 解析文本内容
     * @param {string} text 原始文本
     * @returns {string} 处理后的文本
     */
    parseText(text) {
        text = text.replace(/[\s]+/gm, '\n');
        text = text.replace(/\n+/g, '\n').replace(/^\s+/, '');
        text = text.replace(/\n/g, ' ');
        return text;
    }

    /**
     * 解析HTML获取数组结果
     * @param {string} html HTML内容
     * @param {string} parse 解析规则
     * @returns {Array} 解析结果数组
     */
    pdfa(html, parse) {
        if (!html || !parse) return [];
        parse = this.parseHikerToJq(parse);

        const doc = __drpyCheerioLib().load(html);
        if (PARSE_CACHE) {
            if (this.pdfa_html !== html) {
                this.pdfa_html = html;
                this.pdfa_doc = doc;
            }
        }

        const parses = parse.split(' ');
        let ret = null;
        for (const nparse of parses) {
            ret = this.parseOneRule(doc, nparse, ret);
            if (!ret) return [];
        }

        const res = (ret?.toArray() ?? []).map((item) => {
            const res_html = `${doc(item)}`;
            return res_html ? res_html : '';
        });
        return res;
    }

    /**
     * 解析HTML获取单个值
     * @param {string} html HTML内容
     * @param {string} parse 解析规则
     * @param {string} baseUrl 基础URL
     * @returns {string} 解析结果
     */
    pdfh(html, parse, baseUrl = '') {
        if (!html || !parse) return '';

        const doc = __drpyCheerioLib().load(html);
        if (typeof PARSE_CACHE !== 'undefined' && PARSE_CACHE) {
            if (this.pdfa_html !== html) {
                this.pdfa_html = html;
                this.pdfa_doc = doc;
            }
        }

        // 处理特殊解析规则
        if (parse === 'body&&Text' || parse === 'Text') {
            return this.parseText(doc.text());
        } else if (parse === 'body&&Html' || parse === 'Html') {
            return doc.html();
        }

        let option;
        if (this.contains(parse, '&&')) {
            const parts = parse.split('&&');
            option = parts.pop();
            parse = parts.join('&&');
        }

        parse = this.parseHikerToJq(parse, true);
        const parses = parse.split(' ');

        let ret = null;
        for (const nparse of parses) {
            ret = this.parseOneRule(doc, nparse, ret);
            if (!ret) return '';
        }

        if (option) {
            switch (option) {
                case 'Text':
                    ret = ret ? this.parseText(ret.text()) : '';
                    break;
                case 'Html':
                    ret = ret ? ret.html() : '';
                    break;
                default:
                    const originalRet = ret.clone();
                    const options = option.split('||');
                    for (const opt of options) {
                        ret = originalRet?.attr(opt) || '';
                        // 处理style中的url
                        if (this.contains(opt.toLowerCase(), 'style') && this.contains(ret, 'url(')) {
                            try {
                                ret = ret.match(/url\((.*?)\)/)[1];
                                ret = ret.replace(/^['"]|['"]$/g, '');
                            } catch {
                            }
                        }
                        // 自动拼接URL
                        if (ret && baseUrl) {
                            const needAdd = this.test(URLJOIN_ATTR, opt) && !this.test(SPECIAL_URL, ret);
                            if (needAdd) {
                                ret = ret.includes('http') ? ret.slice(ret.indexOf('http')) : joinUrl(baseUrl, ret);
                            }
                        }
                        if (ret) break;
                    }
            }
        } else { // 增加返回字符串，禁止直接返回pq对象
            ret = `${ret}`;
        }

        return ret;
    }

    /**
     * 解析HTML并自动拼接URL
     * @param {string} html HTML内容
     * @param {string} parse 解析规则
     * @param {string} baseUrl 基础URL
     * @returns {string} 解析结果
     */
    pd(html, parse, baseUrl = '') {
        if (!baseUrl) baseUrl = this.MY_URL;
        return this.pdfh(html, parse, baseUrl);
    }
}

globalThis.pdfh = function (html, parse, base_url = '') {
    const jsp = new Jsoup(base_url);
    return jsp.pdfh(html, parse, base_url);
};

globalThis.pd = function (html, parse, base_url = '') {
    const jsp = new Jsoup(base_url);
    return jsp.pd(html, parse);
};

globalThis.pdfa = function (html, parse) {
    const jsp = new Jsoup();
    return jsp.pdfa(html, parse);
};
