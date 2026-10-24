# 阅读[API](/app/src/main/java/io/legado/app/api/controller)

## 对于[Web](/app/src/main/java/io/legado/app/web/)的配置

您需要先在设置中启用"Web 服务"。

## 使用

### Web

以下说明假设您的操作在本机进行，且开放端口为1122。  
如果您要从远程计算机访问[阅读]()，请将`127.0.0.1`替换成手机IP。

#### 插入单个书源

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)

```
URL = http://127.0.0.1:1122/saveBookSource
Method = POST
```

#### 插入多个书源or订阅源

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)，**为数组格式**。

```
URL = http://127.0.0.1:1122/saveBookSources
URL = http://127.0.0.1:1122/saveRssSources
Method = POST
```

#### 获取书源

```
URL = http://127.0.0.1:1122/getBookSource?url=xxx
URL = http://127.0.0.1:1122/getRssSource?url=xxx
Method = GET
``` 

#### 获取所有书源or订阅源

```
URL = http://127.0.0.1:1122/getBookSources
URL = http://127.0.0.1:1122/getRssSources
Method = GET
```

#### 删除多个书源or订阅源

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookSource.kt)，**为数组格式**。

```
URL = http://127.0.0.1:1122/deleteBookSources
URL = http://127.0.0.1:1122/deleteRssSources
Method = POST
```

#### 调试源

key为书源搜索关键词，tag为源链接

```
URL = ws://127.0.0.1:1235/bookSourceDebug
URL = ws://127.0.0.1:1235/rssSourceDebug
Message = { key: [String], tag: [String] }
```

#### 获取替换规则

```
URL = http://127.0.0.1:1122/getReplaceRules
Method = GET
```

#### 替换规则管理

请求BODY内容为`JSON`字符串，  
替换规则参考[这个文件](/app/src/main/java/io/legado/app/data/entities/ReplaceRule.kt)。

##### 删除

```
URL = http://127.0.0.1:1122/deleteReplaceRule
Method = POST
Body = [ReplaceRule]
```
##### 插入

```
URL = http://127.0.0.1:1122/saveReplaceRule
Method = POST
Body = [ReplaceRule]
```

##### 测试

返回测试文本text替换结果

```
URL = http://127.0.0.1:1122/testReplaceRule
Method = POST
Body = { rule: [ReplaceRule], text: [String] }
```

#### 搜索在线书籍

若想获取对应的书籍的目录正文 请先**插入书籍**以启用缓存，如果试读后决定不添加到书籍，请**删除书籍**

```
URL = ws://127.0.0.1:1235/searchBook
Message = { key: [String] }
```

#### 插入书籍

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/Book.kt)。

```
URL = http://127.0.0.1:1122/saveBook
Method = POST
```

#### 删除书籍

```
URL = http://127.0.0.1:1122/deleteBook
Method = POST
```

#### 获取书籍

```
URL = http://127.0.0.1:1122/getBookshelf?groupId=xxx
Method = GET
```

获取APP内的指定分组的书籍，留空groupId获取所有书籍。

#### 获取所有分组

```
URL = http://127.0.0.1:1122/getGroups
Method = GET
```

获取APP内的所有分组（包含默认分组和用户创建）。

#### 获取书籍章节列表

```
URL = http://127.0.0.1:1122/getChapterList?url=xxx
Method = GET
```

获取指定图书的章节列表。

#### 获取书籍内容

```
URL = http://127.0.0.1:1122/getBookContent?url=xxx&index=1
Method = GET
```

获取指定图书的第`index`章节的文本内容。

#### 获取封面

```
URL = http://127.0.0.1:1122/cover?path=xxxxx
Method = GET
```

#### 获取正文图片

```
URL = http://127.0.0.1:1122/image?url=${bookUrl}&path=${picUrl}&width=${width}
Method = GET
```

#### 保存书籍进度

请求BODY内容为`JSON`字符串，  
格式参考[这个文件](/app/src/main/java/io/legado/app/data/entities/BookProgress.kt)。

```
URL = http://127.0.0.1:1122/saveBookProgress
Method = POST
```
