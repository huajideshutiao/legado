<template>
  <div class="help-links">
    <a class="web-link" href="/help/#appHelp" target="_blank">APP帮助文档</a>
    <a class="web-link" href="/help/#ruleHelp" target="_blank">书源制作教程</a>
    <a class="web-link" href="/help/#jsHelp" target="_blank">js变量和函数</a>
    <a class="web-link" href="/help/#xpathHelp" target="_blank">xpath语法教程</a>
    <a class="web-link" href="/help/#regexHelp" target="_blank">正则表达式教程</a>
    <a class="web-link" href="/help/#txtTocRuleHelp" target="_blank">txt目录正则说明</a>
    <a class="web-link" href="/help/#debugHelp" target="_blank">书源调试说明</a>
    <a class="web-link" href="/help/#httpTTSHelp" target="_blank">在线朗读规则</a>
    <a class="web-link" href="/help/#webDavBookHelp" target="_blank">WebDav书籍简明使用教程</a>
    <a class="web-link" href="/help/#webDavHelp" target="_blank">WebDav备份教程</a>
    <a class="web-link" href="https://regexr-cn.com/" target="_blank">正则表达式在线验证工具</a>
    <div style="margin-top: 20px">
      <p class="web-text web-text--secondary"><code>^$()[]{}.?+*|</code> 这些是Java正则特殊符号,匹配需转义</p>
      <p class="web-text web-text--secondary"><code>(?s)</code> 前缀表示跨行解析</p>
      <p class="web-text web-text--secondary"><code>(?m)</code> 前缀表示逐行匹配</p>
      <p class="web-text web-text--secondary"><code>(?i)</code> 前缀表示忽略大小写</p>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.help-links {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.web-link {
  padding: 2px 0;
}
</style>
