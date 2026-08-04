import legado from 'liblegado_napi.so';

/**
 * ArkTS 侧文本/图片操作菜单处理器 (阅读页长按选字/长按图片浮动菜单, 平台原生 ArkUI 叠层实现)。
 *
 * 对标 Android 原版 TextActionMenu (ActionMode.TYPE_FLOATING 跟随选区) 与
 * ReadBookActivity.onImageLongPress 图片菜单 (查看/刷新/保存/选择目录):
 * - KMP OhosReaderPlatformProvider.onTextSelected → OhosNativeBridge.showTextActionMenu
 *   → tsfn dispatch → [handleShowTextActionMenu] 更新菜单状态 (Index.ets 叠层读取);
 *   图片长按 → OhosNativeBridge.showImageActionMenu (同 tsfn, payload 带 type="image" + src)
 * - 文本菜单项与 Android 原版 content_select_action.xml 一致 (替换/复制/书签/朗读/查词/
 *   全文搜索/浏览器/分享); 图片菜单项为查看/刷新/保存到相册 (鸿蒙无"选择目录"概念,
 *   平台适配为保存到系统相册, 见 Index.ets saveImageToGallery)
 * - 菜单项点击 → [fireTextAction] → legado.textActionCallback → C++ → KMP 动作分发
 * - 点遮罩收起 → [dismissTextActionMenu] → 回送 `__dismiss` → KMP 取消页内选择
 *   (对标原版 onMenuActionFinally → cancelSelect)
 *
 * # JSON 协议 (与 OhosNativeBridge.kt TextActionMenuPayload 对齐)
 * - 请求 (Kotlin → ArkTS): `{ text: string, x: number, y: number, src?: string, type?: string }`
 *   (type = "text" | "image", 缺省按 "text"; x/y = -1 表示隐藏)
 * - 回调 (ArkTS → Kotlin): `{ action: "replace|copy|bookmark|aloud|dict|search_content|browser|share|refresh|__dismiss", text: string, src: string }`
 */

/** 菜单状态 (由 Index.ets 注入 setter, @State 驱动叠层显隐/定位)。 */
export interface TextActionMenuData {
  visible: boolean;
  text: string;
  x: number;
  y: number;
  /** 图片 src (type="image" 时携带, 图片菜单动作参数)。 */
  src: string;
  /** 菜单类型: "text" = 文本操作菜单 / "image" = 图片操作菜单。 */
  type: string;
}

interface TextActionMenuPayload {
  text: string;
  x: number;
  y: number;
  src?: string;
  type?: string;
}

/** 菜单状态更新回调 (Index.ets 注入, 内部赋给 @State)。 */
export type TextActionMenuCallback = (data: TextActionMenuData) => void;

let menuCallback: TextActionMenuCallback | undefined;

/**
 * 初始化文本菜单桥 (由 Index.ets aboutToAppear 调用, 注入 @State setter)。
 *
 * @param cb 菜单状态更新回调
 */
export function initTextActionBridge(cb: TextActionMenuCallback): void {
  menuCallback = cb;
}

/**
 * 处理来自 Kotlin 的文本菜单请求 (由 EntryAbility 注册的 tsfn 回调调用)。
 *
 * @param json 请求 JSON 字符串, 形如 `{ text: '<选中文本>', x: <number>, y: <number> }`
 */
export function handleShowTextActionMenu(json: string): void {
  try {
    const payload = JSON.parse(json) as TextActionMenuPayload;
    // x/y = -1: KMP 请求隐藏菜单
    if (payload.x < 0 || payload.y < 0) {
      menuCallback?.({ visible: false, text: '', x: 0, y: 0, src: '', type: 'text' });
      return;
    }
    menuCallback?.({
      visible: true,
      text: payload.text,
      x: payload.x,
      y: payload.y,
      src: payload.src ?? '',
      type: payload.type ?? 'text',
    });
  } catch (e) {
    console.error('[TextActionBridge] show menu failed: ' + JSON.stringify(e) + ', json=' + json);
  }
}

/** 菜单项 (对标 Android 原版 content_select_action.xml 顺序: replace/copy/bookmark/aloud/dict/search_content/browser/share)。 */
export const TEXT_ACTION_ITEMS: { label: string; action: string }[] = [
  { label: '替换', action: 'replace' },
  { label: '复制', action: 'copy' },
  { label: '书签', action: 'bookmark' },
  { label: '朗读', action: 'aloud' },
  { label: '查词', action: 'dict' },
  { label: '全文搜索', action: 'search_content' },
  { label: '浏览器', action: 'browser' },
  { label: '分享', action: 'share' },
];

/**
 * 图片菜单项 (对标原版 ReadBookActivity.onImageLongPress 图片菜单, 鸿蒙适配:
 * 无"选择目录"概念 → 保存到相册; 查看 = 叠层全屏预览; 刷新 = 回送 KMP 清缓存重排)。
 */
export const IMAGE_ACTION_ITEMS: { label: string; action: string }[] = [
  { label: '查看', action: 'view' },
  { label: '刷新', action: 'refresh' },
  { label: '保存到相册', action: 'save' },
];

/**
 * 菜单项点击 (Index.ets 调): 回送动作到 KMP 并收起菜单。
 *
 * @param action 动作 key (见 TEXT_ACTION_ITEMS / IMAGE_ACTION_ITEMS)
 * @param text 选中文本 (文本菜单)
 * @param src 图片 src (图片菜单, 如 refresh 回送 KMP 清缓存重排)
 */
export function fireTextAction(action: string, text: string, src: string): void {
  menuCallback?.({ visible: false, text: '', x: 0, y: 0, src: '', type: 'text' });
  try {
    legado.textActionCallback(0, JSON.stringify({ action: action, text: text, src: src }));
  } catch (e) {
    console.error('[TextActionBridge] fire action failed: ' + JSON.stringify(e));
  }
}

/**
 * 菜单收起 (点遮罩): 回送 `__dismiss` 到 KMP (取消页内选择, 对标原版
 * onMenuActionFinally → readView.cancelSelect()) 并隐藏菜单。
 */
export function dismissTextActionMenu(): void {
  menuCallback?.({ visible: false, text: '', x: 0, y: 0, src: '', type: 'text' });
  try {
    legado.textActionCallback(0, JSON.stringify({ action: '__dismiss', text: '', src: '' }));
  } catch (e) {
    console.error('[TextActionBridge] dismiss failed: ' + JSON.stringify(e));
  }
}
