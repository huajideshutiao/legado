package io.legado.app.help.image

/** iOS 端正文图片表: 进程内字节 LRU ([InMemoryBodyImageStore])。 */
internal actual fun bodyImageStore(): BodyImageStore = InMemoryBodyImageStore
