package io.legado.app.ui.preview

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.Review
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.SourceFilterRule

/**
 * Preview 共用假数据。
 *
 * 各 *Previews.kt 若只自己用则保留文件内 private 常量; 跨文件复用的实体放这里,
 * 字段填真实感数据 (中文书名/作者/章节), 纯内存构造, 不触碰 DB/网络。
 */

/** 书籍: 长篇科幻, 有阅读进度与最新章节。 */
val previewBookSample = Book(
    name = "三体",
    author = "刘慈欣",
    bookUrl = "preview://book/1",
    tocUrl = "preview://book/1/toc",
    origin = BookType.localTag,
    kind = "科幻;硬科幻;长篇",
    intro = "文革期间, 一次偶然的星际通讯让三体文明锁定地球; 面壁计划与黑暗森林法则由此展开。",
    coverUrl = "https://preview.invalid/cover1.jpg",
    durChapterTitle = "第十二章 黑暗森林",
    latestChapterTitle = "第三十六章 末日之战",
    durChapterIndex = 11,
    totalChapterNum = 36,
    latestChapterTime = 1_700_000_000_000,
    lastCheckCount = 3,
)

/** 书籍: 无封面无进度的新书 (空态/占位符验证)。 */
val previewBookFresh = Book(
    name = "活着",
    author = "余华",
    bookUrl = "preview://book/2",
    tocUrl = "preview://book/2/toc",
    origin = BookType.localTag,
    kind = "文学;当代",
    intro = "一个人和他命运之间的友情, 讲述眼泪的宽广和丰富。",
    durChapterTitle = "",
    latestChapterTitle = "",
    totalChapterNum = 0,
)

/** 分组: 常规分组。 */
val previewGroupSample = BookGroup(
    groupId = 1,
    groupName = "正在追",
    cover = "https://preview.invalid/group.jpg",
)

/** 章节列表: 含卷名/VIP/未缓存混合场景。 */
val previewChapters: List<BookChapter> = listOf(
    BookChapter(
        url = "preview://ch/vol1",
        title = "第一卷 地球往事",
        isVolume = true,
        bookUrl = previewBookSample.bookUrl,
        index = 0,
    ),
    BookChapter(
        url = "preview://ch/1",
        title = "第一章 科学边界",
        bookUrl = previewBookSample.bookUrl,
        index = 1,
        wordCount = "3.2万字",
    ),
    BookChapter(
        url = "preview://ch/2",
        title = "第二章 台球",
        bookUrl = previewBookSample.bookUrl,
        index = 2,
        isVip = true,
        wordCount = "2.8万字",
    ),
    BookChapter(
        url = "preview://ch/3",
        title = "第三章 射手与农场主",
        bookUrl = previewBookSample.bookUrl,
        index = 3,
        isVip = true,
        isPay = true,
        wordCount = "4.1万字",
    ),
)

/** 书签列表: 跨两本书, 用于验证 AllBookmarkScreen 的按书分组吸顶。 */
val previewBookmarks: List<Bookmark> = listOf(
    Bookmark(
        time = 1_700_000_000_000,
        bookName = "三体",
        bookAuthor = "刘慈欣",
        chapterIndex = 11,
        chapterPos = 320,
        chapterName = "第十二章 黑暗森林",
        bookText = "宇宙就是一座黑暗森林, 每个文明都是带枪的猎人。",
        content = "核心设定, 反复回看",
    ),
    Bookmark(
        time = 1_700_100_000_000,
        bookName = "三体",
        bookAuthor = "刘慈欣",
        chapterIndex = 20,
        chapterPos = 88,
        chapterName = "第二十一章 面壁者",
        bookText = "我是一个面壁者。",
        content = "",
    ),
    Bookmark(
        time = 1_700_200_000_000,
        bookName = "活着",
        bookAuthor = "余华",
        chapterIndex = 3,
        chapterPos = 512,
        chapterName = "第四章",
        bookText = "人是为了活着本身而活着, 而不是为了活着之外的任何事物而活着。",
        content = "序言里的话",
    ),
)

/** 替换规则: 正则 / 纯文本 / 已禁用三种。 */
val previewReplaceRules: List<ReplaceRule> = listOf(
    ReplaceRule(
        id = 1,
        name = "去除广告行",
        group = "净化",
        pattern = "^.*(最新章节|请记住本站).*$",
        replacement = "",
        scopeContent = true,
        isRegex = true,
    ),
    ReplaceRule(
        id = 2,
        name = "书名号统一",
        group = "排版",
        pattern = "《",
        replacement = "「",
        scopeTitle = true,
        scopeContent = true,
        isRegex = false,
    ),
    ReplaceRule(
        id = 3,
        name = "空行压缩(已停用)",
        group = "排版",
        pattern = "\\n{3,}",
        replacement = "\n\n",
        isEnabled = false,
        isRegex = true,
    ),
)

/** 字典规则。 */
val previewDictRules: List<DictRule> = listOf(
    DictRule(name = "百度汉语", urlRule = "https://dict.baidu.com/s?wd={{key}}", showRule = "class.tab-content@html", sortNumber = 0),
    DictRule(name = "汉典", urlRule = "https://www.zdic.net/hans/{{key}}", showRule = "class.content@html", sortNumber = 1),
    DictRule(name = "有道(已停用)", urlRule = "https://dict.youdao.com/w/{{key}}", showRule = "id.phrsListTab@html", enabled = false, sortNumber = 2),
)

/** 书源过滤规则。 */
val previewFilterRules: List<SourceFilterRule> = listOf(
    SourceFilterRule(id = "f1", name = "屏蔽同人", pattern = "同人|二创", fields = "name,kind", order = 0),
    SourceFilterRule(id = "f2", name = "屏蔽短篇", pattern = "^短篇", fields = "kind", order = 1, enabled = false),
)

/** WebDav 服务器 (config 为 WebDavConfig JSON)。 */
val previewServers: List<Server> = listOf(
    Server(
        id = 1,
        name = "坚果云",
        type = Server.TYPE.WEBDAV,
        config = """{"url":"https://dav.jianguoyun.com/dav/","username":"reader@example.com","password":"******"}""",
    ),
    Server(
        id = 2,
        name = "自建 NAS",
        type = Server.TYPE.WEBDAV,
        config = """{"url":"https://nas.local/dav/","username":"admin","password":"******"}""",
    ),
)

/** 段评列表: 含图片/点赞/回复各状态。 */
val previewReviews: List<Review> = listOf(
    Review(
        id = "r1",
        avatar = "https://preview.invalid/avatar1.jpg",
        name = "叶文洁",
        content = "不要回答! 不要回答! 不要回答!",
        postTime = "2 小时前",
        extra = "1 楼 · 北京",
        voteUpCount = 1287,
        replyCount = 42,
        voted = true,
    ),
    Review(
        id = "r2",
        avatar = null,
        name = "罗辑",
        content = "面壁者计划的核心, 在于没有人知道面壁者真正在想什么。这段读了三遍还是起鸡皮疙瘩。",
        postTime = "昨天",
        extra = "2 楼",
        voteUpCount = 63,
        replyCount = 0,
        images = listOf("https://preview.invalid/img1.jpg", "https://preview.invalid/img2.jpg"),
    ),
    Review(
        id = "r3",
        name = "章北海",
        content = "前进四。",
        postTime = "3 天前",
        voteUpCount = 0,
        replyCount = 5,
        votedDown = true,
    ),
)
