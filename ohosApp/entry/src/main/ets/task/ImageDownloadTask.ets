import legado from 'liblegado_napi.so';

/**
 * taskpool worker 任务: 调 K/N 带书源 header 的下载管线 (base64 返回)。
 *
 * 必须离主线程执行: K/N 下载内部走 invokeHttpSync → tsfn → 主线程处理回调,
 * 主线程直接调用会因主线程被阻塞而无法处理 HTTP 回调 → 死锁超时
 * (与 bookshelfList 等业务函数同约束, 见 Index.d.ts downloadImageBytes 注释)。
 *
 * 独立成文件而非放在 pages/Index.ets: taskpool worker 会在自己的 VM 里重新执行
 * 宿主模块顶层, 若宿主是 @Entry 页面则连带在非 UI 线程实例化 XComponentController
 * 等 ArkUI 对象, 初始化抛异常会让 taskpool.execute reject, 静默退化成裸 http 下载,
 * 丢掉本管线唯一价值 (书源 header 防盗链)。故本模块顶层只允许 napi 导入。
 */
@Concurrent
export function loadImageBytesTask(url: string): string {
  return legado.downloadImageBytes(url);
}
