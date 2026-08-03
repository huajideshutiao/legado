package io.legado.app.help.image

/**
 * JS `image` 别名的图片解密 API，只服务解密/反爬重排场景，不做通用图片库。
 *
 * 纯签名面（ByteArray/String/Int/List/Map 与不透明句柄 [ImageRef]）落 commonMain；
 * 平台实现（android=Bitmap，ios=CoreGraphics，ohos=PixelMap）经
 * [io.legado.app.model.script.JsBindingInjector.registerImageOps] 注入——依赖方向 app→shared，
 * 走 provider 注册而非 expect/actual（同 appString/AppLogHost 先例）。
 *
 * 注：`decode(InputStream)` 流式重载非 common 签名，由 JVM/Android 实现类自行附加
 * （JS 分派表按实现类方法面生成，JS 可见面不变）。
 */
interface ImageOps {

    /** 解码图片字节，失败抛异常。 */
    fun decode(bytes: ByteArray): ImageRef

    /** 解码 base64 字符串（容忍 `data:image/...;base64,` 前缀）。 */
    fun decode(base64: String): ImageRef

    /** 编码为字节。format: `png`/`jpg`/`webp`；quality 0-100（png 无损，忽略）。 */
    fun encode(img: ImageRef, format: String, quality: Int): ByteArray

    /** 均分切块，行优先（先左→右再上→下），除不尽的余数并入最后一行/列。 */
    fun split(img: ImageRef, rows: Int, cols: Int): List<ImageRef>

    /** 按序拼接。direction: `h` 水平（左→右）/ `v` 垂直（上→下）。 */
    fun stitch(imgs: List<ImageRef>, direction: String): ImageRef

    /** 裁剪 (x,y) 起点的 w×h 区域，越界抛异常。 */
    fun crop(img: ImageRef, x: Int, y: Int, w: Int, h: Int): ImageRef

    /** 尺寸，返回 `{w,h}`。 */
    fun size(img: ImageRef): Map<String, Int>
}

/**
 * 不透明图片句柄，内持平台原生图（android=Bitmap）。
 * split/stitch/crop 直接操作原生图不走中间编解码，encode 才输出字节；由 GC 回收。
 */
interface ImageRef
