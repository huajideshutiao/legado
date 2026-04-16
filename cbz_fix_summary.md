# CBZ文件操作问题修复总结

## 问题描述
在远程书籍页添加一个cbz文件会导致：
1. 某种程度上不能访问本地文件
2. 重启应用后应用会直接闪退
3. 编译安装时提示 "Couldn't terminate previous instance of app" 问题

## 问题原因
经过分析，问题的根本原因是资源泄漏：

1. 当添加远程cbz文件时，`CbzFile` 类会创建一个静态实例 `eFile` 来缓存cbz文件的处理对象
2. 这个静态实例会持有对 `zipFile`、`fileDescriptor` 等资源的引用
3. 当应用退出时，这些资源没有被正确释放，导致资源泄漏
4. 资源泄漏会导致文件描述符泄漏，从而导致应用闪退和无法终止先前实例的问题

## 解决方案
在 `LifecycleHelp.kt` 中添加了一个初始化块，注册了一个应用退出监听器：

```kotlin
init {
    // 注册应用退出监听器，释放资源
    setOnAppFinishedListener {
        // 释放CbzFile资源
        io.legado.app.model.localBook.CbzFile.clear()
    }
}
```

这样，当应用退出时，监听器会调用 `CbzFile.clear()` 方法来释放静态 `eFile` 实例，从而释放所有与cbz文件相关的资源。

## 修复效果
1. 应用退出时会正确释放cbz文件相关的资源，避免资源泄漏
2. 重启应用时不会因为资源泄漏而闪退
3. 编译安装时不会因为无法终止先前实例而失败
4. 本地文件访问不会受到影响

## 技术细节
- `CbzFile.clear()` 方法会调用 `close()` 方法来释放 `zipFile` 和 `fileDescriptor` 等资源
- `LifecycleHelp` 会在所有Activity和Service都销毁时触发应用退出事件
- 监听器会在应用退出时调用 `CbzFile.clear()` 方法，确保所有资源都被正确释放

这个修复方案简单有效，不会影响应用的其他功能，同时解决了cbz文件操作导致的资源泄漏问题。