import { UIContext } from '@ohos.arkui.UIContext';
import promptAction from '@ohos.promptAction';
import { BridgeRequest, BridgeResponse, dispatchBridgeRequest } from './BridgeDispatch';

/**
 * ArkTS 侧系统对话框处理器 (桥接 @ohos.promptAction, 不自造渲染)。
 *
 * 接收 Kotlin OhosNativeBridge.invokeDialogSync 经 tsfn dispatch 过来的对话框请求,
 * 用系统提示对话框 (UIContext.getPromptAction().showDialog) 弹出候选列表 / 是-否确认框,
 * 用自绘自定义对话框 (UIContext.getPromptAction().openCustomDialog) 弹出多字段输入框,
 * 拿到用户选择后经结果回送函数交还 Kotlin (唤醒阻塞中的 CompletableDeferred)。
 *
 * # 三种能力 (与 Kotlin 侧 action 逐字对齐)
 * - `select`:  显示带标题的候选列表, 回传选中的下标;
 * - `confirm`: 显示是-否确认框, 回传是否确认;
 * - `form`:    显示多字段输入对话框 (标题 + 逐字段标签与输入框 + 各提交按钮 + 取消),
 *              回传点中的动作键与各字段当前文本;
 * 用户取消 (点遮罩/返回键) 或关闭时回传"未选择"(`{ ok: true, cancelled: true }`), 不是某个下标/布尔/动作键。
 *
 * # JSON 协议 (与 Kotlin OhosNativeBridge.DialogBridgeRequest / 结果 DTO 对齐)
 *
 * ## 请求 (Kotlin → ArkTS)
 * ```
 * { "requestId": <number>, "action": "select" | "confirm" | "form", "payload": "<JSON string>" }
 * ```
 * payload (二次 JSON.parse):
 * - select:  `{ "title": "<标题>", "items": ["<候选1>", ...] }`
 * - confirm: `{ "title": "<标题>", "message": "<正文>?", "confirmText": "<是>", "cancelText": "<否>" }`
 * - form:    `{ "title": "<标题>", "message": "<正文>?",
 *              "fields": [{ "key", "label", "initial?", "hint?", "numeric?" }, ...],
 *              "actions": [{ "key", "text" }, ...], "cancelText": "<取消>?" }`
 *
 * ## 响应 (ArkTS → Kotlin, 由结果回送函数回送)
 * - select 成功:  `{ ok: true, index: <选中下标> }`
 * - confirm 成功: `{ ok: true, confirmed: <是否点了"是"> }`
 * - form 成功:    `{ ok: true, action: "<动作键>", values: { "<字段键>": "<当前文本>", ... } }`
 * - 取消/关闭:    `{ ok: true, cancelled: true }` (未选择)
 * - 失败:         `{ ok: false, error: "<原因>" }`
 *
 * # 为什么走 tsfn + 统一平台事件, 而不新加 napi 符号
 * 请求侧复用 window tsfn (OhosNativeBridge.WindowCommand action="showDialog",
 * 同 exitApplication 复用先例), 由 EntryAbility 的 window callback 转交本模块;
 * 结果侧经 legado.platformEvent (type="dialogResult", 同 colorMode 等后加事件先例) 回送。
 * 新建独立 tsfn + 回调符号需改 ohosApp/entry/src/main/cpp 下的 legado_napi.cpp 与
 * liblegado_napi/Index.d.ts, 二者不在本次改动的文件域内 (见 Kotlin 侧同段注释)。
 *
 * # 候选列表的按钮上限与 form 的自绘理由 (为什么不换更"专业"的菜单类 API)
 * 本机 SDK 里 ActionMenuOptions.buttons 是固定 6 元组 ("One to six buttons are supported"),
 * 而 ShowDialogOptions.buttons 是无上限的 `Array<Button>` 且自 API 10 起文档口径为
 * "More than one buttons is supported" —— 压缩包内条目数不定 (可能远超 6), 只有后者能承载,
 * 故 select/confirm 两种能力统一用 showDialog (返回 index)。
 * 输入框不在 ShowDialogOptions 的字段里 (它只有 title/message/buttons), 故 `form` 用
 * UIContext.getPromptAction().openCustomDialog (CustomDialogOptions.builder: CustomBuilder,
 * API 12) 自绘内容, 关闭用同一 PromptAction 的 closeCustomDialog(dialogId)。
 *
 * # UIContext 注入
 * getPromptAction() 需要 UIContext, 由 Index.ets aboutToAppear 调
 * [initSysDialogBridgeUiContext] 注入 (与 SystemBridgeHandler.initSystemBridgeUiContext 同模式);
 * 未注入时直接按未选择降级 (打 error 日志, 不崩)。
 */

/** select 请求 payload (Kotlin → ArkTS, 与 Kotlin OhosDialogSelectPayload 对齐)。 */
interface DialogSelectPayload {
  title?: string;
  items?: string[];
}

/** confirm 请求 payload (Kotlin → ArkTS, 与 Kotlin OhosDialogConfirmPayload 对齐)。 */
interface DialogConfirmPayload {
  title?: string;
  message?: string;
  confirmText?: string;
  cancelText?: string;
}

/** form 请求里的一个输入字段 (Kotlin → ArkTS, 与 Kotlin OhosDialogFormField 对齐)。 */
interface DialogFormField {
  /** 字段键 (回传 values 的键)。 */
  key: string;
  /** 字段名 (输入框上方的标签)。 */
  label: string;
  /** 初始文本。 */
  initial?: string;
  /** 输入框占位提示。 */
  hint?: string;
  /** 是否用数字键盘。 */
  numeric?: boolean;
}

/** form 请求里的一个提交按钮 (Kotlin → ArkTS, 与 Kotlin OhosDialogFormAction 对齐)。 */
interface DialogFormAction {
  /** 动作键 (回传 action 的值)。 */
  key: string;
  /** 按钮文案 (由 Kotlin 侧按语言给, ArkTS 不拼文案)。 */
  text: string;
}

/** form 请求 payload (Kotlin → ArkTS, 与 Kotlin OhosDialogFormPayload 对齐)。 */
interface DialogFormPayload {
  title?: string;
  message?: string;
  fields?: DialogFormField[];
  actions?: DialogFormAction[];
  /** 取消按钮文案; 缺省时只能拍遮罩/返回键关闭。 */
  cancelText?: string;
}

/** form 对话框内容参数 (ArkTS 内部; 两个回调由 showForm 注入)。 */
interface DialogFormParams {
  title: string;
  /** 正文字 (无正文为空串)。 */
  message: string;
  fields: DialogFormField[];
  actions: DialogFormAction[];
  /** 取消按钮文案 (无取消按钮为空串)。 */
  cancelText: string;
  /** 点某个提交按钮: 带该按钮的 key 与各字段当前文本。 */
  onSubmit: (actionKey: string, values: string[]) => void;
  /** 点取消。 */
  onCancel: () => void;
}

/** form 对话框的打开状态 (dialogId 要等 openCustomDialog 的 Promise 才有)。 */
interface FormDialogState {
  dialogId: number;
  settled: boolean;
}

// 由 EntryAbility 注入的结果回送函数类型 (内部调 legado.platformEvent type='dialogResult')
export type DialogResultCallback = (requestId: number, resultJson: string) => void;

/** 响应 (ArkTS → Kotlin, 与 Kotlin OhosDialogResult 对齐)。 */
interface DialogResult extends BridgeResponse {
  /** select: 选中下标 (0-based; 仅 ok=true 且未 cancelled 时有效)。 */
  index?: number;
  /** confirm: 是否点了"是" (仅 ok=true 且未 cancelled 时有效)。 */
  confirmed?: boolean;
  /** form: 点中的动作键 (仅 ok=true 且未 cancelled 时有效)。 */
  action?: string;
  /** form: 各字段当前文本 (键为字段键)。 */
  values?: Record<string, string>;
  /** 未选择 (用户取消/关闭对话框)。 */
  cancelled?: boolean;
}

// 由 Index.ets aboutToAppear 注入的 UIContext (getPromptAction() 必需)
let uiContext: UIContext | undefined;

/**
 * 注入 UIContext (由 Index.ets aboutToAppear 调用)。
 *
 * @param ctx 当前窗口 UIContext (用于 getPromptAction().showDialog)
 */
export function initSysDialogBridgeUiContext(ctx: UIContext): void {
  uiContext = ctx;
  console.info('[SysDialogBridge] uiContext injected');
}

/**
 * 处理来自 Kotlin 的对话框请求 (由 EntryAbility 的 window callback 转交)。
 *
 * @param requestJson 控制面请求 JSON 字符串 (含 requestId/action/payload)
 * @param callback 回送结果的函数 (EntryAbility 注入, 内部调 legado.platformEvent
 *   type="dialogResult" 交还 Kotlin)
 */
export function handleDialogRequest(
  requestJson: string,
  callback: DialogResultCallback
): void {
  dispatchBridgeRequest(requestJson, 'SysDialogBridge', handleDialogAction, callback);
}

/** 按 action 分发 (未知 action 回 ok:false, 与其余 handler 同口径)。 */
function handleDialogAction(req: BridgeRequest): Promise<BridgeResponse> {
  const payloadJson: string = req.payload ?? '';
  switch (req.action) {
    case 'select':
      return selectAction(payloadJson);
    case 'confirm':
      return confirmAction(payloadJson);
    case 'form':
      return formAction(payloadJson);
    default:
      return Promise.resolve({ ok: false, error: 'unknown action: ' + req.action } as BridgeResponse);
  }
}

// ===== select: 带标题的候选列表 → 选中下标 =====

/** 候选列表请求: 解析 payload → 弹系统对话框 → 回 index / cancelled。 */
function selectAction(payloadJson: string): Promise<BridgeResponse> {
  let payload: DialogSelectPayload;
  try {
    payload = JSON.parse(payloadJson.length > 0 ? payloadJson : '{}') as DialogSelectPayload;
  } catch (e) {
    console.error('[SysDialogBridge] parse select payload failed: ' + payloadJson);
    return Promise.resolve({ ok: false, error: 'parse payload failed' } as BridgeResponse);
  }
  const items: string[] = payload.items ?? [];
  if (items.length === 0) {
    // 无候选可弹 (调用方本应先判空): 明确失败而非弹一个空框
    return Promise.resolve({ ok: false, error: 'select: items 为空' } as BridgeResponse);
  }
  return showSelectList(payload.title ?? '', items).then((index?: number): BridgeResponse => {
    if (index == null) {
      return { ok: true, cancelled: true } as DialogResult;
    }
    return { ok: true, index: index } as DialogResult;
  });
}

/**
 * 弹带标题的候选列表, 返回选中下标; 用户取消/关闭返回 undefined (未选择)。
 *
 * 每个候选一个按钮, 按钮文案即候选文案 (与 Android selector / iOS UIAlertController
 * 的多 action 同形), 系统对话框自身负责长列表的排版与滚动。
 */
function showSelectList(title: string, items: string[]): Promise<number | undefined> {
  const context = uiContext;
  if (context == null) {
    console.error('[SysDialogBridge] select failed: uiContext 未注入 (Index.ets 未调用 initSysDialogBridgeUiContext)');
    return Promise.resolve(undefined);
  }
  const buttons: promptAction.Button[] = [];
  for (const item of items) {
    buttons.push({ text: item, color: $r('app.color.app_primary') });
  }
  const options: promptAction.ShowDialogOptions = { title: title, buttons: buttons };
  try {
    return context.getPromptAction().showDialog(options)
      .then((resp: promptAction.ShowDialogSuccessResponse): number | undefined => {
        const index: number = (resp != null) ? resp.index : -1;
        if (index >= 0 && index < items.length) {
          return index;
        }
        console.warn('[SysDialogBridge] select returned unexpected index: ' + index);
        return undefined;
      })
      .catch((err: Error): number | undefined => {
        // 用户点遮罩/返回键关闭 → promise reject, 按未选择处理 (见模块头协议)
        console.info('[SysDialogBridge] select cancelled: ' + JSON.stringify(err));
        return undefined;
      });
  } catch (e) {
    console.error('[SysDialogBridge] select failed: ' + JSON.stringify(e));
    return Promise.resolve(undefined);
  }
}

// ===== confirm: 是-否确认框 → 是否确认 =====

/** 确认框请求: 解析 payload → 弹系统对话框 → 回 confirmed / cancelled。 */
function confirmAction(payloadJson: string): Promise<BridgeResponse> {
  let payload: DialogConfirmPayload;
  try {
    payload = JSON.parse(payloadJson.length > 0 ? payloadJson : '{}') as DialogConfirmPayload;
  } catch (e) {
    console.error('[SysDialogBridge] parse confirm payload failed: ' + payloadJson);
    return Promise.resolve({ ok: false, error: 'parse payload failed' } as BridgeResponse);
  }
  return showConfirm(payload).then((confirmed?: boolean): BridgeResponse => {
    if (confirmed == null) {
      return { ok: true, cancelled: true } as DialogResult;
    }
    return { ok: true, confirmed: confirmed } as DialogResult;
  });
}

/**
 * 弹是-否确认框, 第一个按钮为"是"、第二个为"否"; 点"是"回 true, 点"否"回 false,
 * 取消/关闭回 undefined (未选择)。按钮文案由 Kotlin 侧按语言给 (syncGetString),
 * ArkTS 侧不自行拼文案。
 */
function showConfirm(payload: DialogConfirmPayload): Promise<boolean | undefined> {
  const context = uiContext;
  if (context == null) {
    console.error('[SysDialogBridge] confirm failed: uiContext 未注入 (Index.ets 未调用 initSysDialogBridgeUiContext)');
    return Promise.resolve(undefined);
  }
  const confirmText: string = payload.confirmText ?? '';
  const cancelText: string = payload.cancelText ?? '';
  const message: string = payload.message ?? '';
  const buttons: promptAction.Button[] = [
    { text: confirmText, color: $r('app.color.app_primary') },
    { text: cancelText, color: $r('app.color.app_primary') }
  ];
  const options: promptAction.ShowDialogOptions = { title: payload.title ?? '', buttons: buttons };
  if (message.length > 0) {
    options.message = message;
  }
  try {
    return context.getPromptAction().showDialog(options)
      .then((resp: promptAction.ShowDialogSuccessResponse): boolean | undefined => {
        const index: number = (resp != null) ? resp.index : -1;
        if (index < 0) {
          return undefined;
        }
        return index === 0;
      })
      .catch((err: Error): boolean | undefined => {
        console.info('[SysDialogBridge] confirm cancelled: ' + JSON.stringify(err));
        return undefined;
      });
  } catch (e) {
    console.error('[SysDialogBridge] confirm failed: ' + JSON.stringify(e));
    return Promise.resolve(undefined);
  }
}

// ===== form: 多字段输入对话框 → 动作键 + 各字段值 =====

/** form 对话框内容的默认参数 (struct 成员必须初始化; 实际始终由 showForm 传入)。 */
const DEFAULT_FORM_PARAMS: DialogFormParams = {
  title: '',
  message: '',
  fields: [],
  actions: [],
  cancelText: '',
  onSubmit: (): void => {},
  onCancel: (): void => {}
};

/**
 * form 对话框内容 (自绘): 标题 + 逐字段标签与输入框 + 各提交按钮 + 取消。
 *
 * 各字段值不用 @State 承载: TextInput 自己持有输入文本, 本组件只在点提交时把值数组
 * 交回 [DialogFormParams.onSubmit], 无需触发重渲染。
 */
@Component
struct DialogFormContent {
  params: DialogFormParams = DEFAULT_FORM_PARAMS;
  private values: string[] = [];

  aboutToAppear(): void {
    this.values = this.params.fields.map((field: DialogFormField): string => field.initial ?? '');
  }

  build() {
    Column() {
      Text(this.params.title)
        .fontSize(18)
        .fontWeight(FontWeight.Medium)
        .width('100%')
      if (this.params.message.length > 0) {
        Text(this.params.message)
          .fontSize(14)
          .width('100%')
          .margin({ top: 4 })
      }
      ForEach(this.params.fields, (field: DialogFormField, index: number) => {
        Column() {
          Text(field.label)
            .fontSize(14)
            .width('100%')
          TextInput({ text: field.initial ?? '', placeholder: field.hint ?? '' })
            .type(field.numeric === true ? InputType.Number : InputType.Normal)
            .width('100%')
            .onChange((value: string): void => {
              this.values[index] = value;
            })
        }
        .width('100%')
        .margin({ top: 12 })
      }, (field: DialogFormField): string => field.key)
      Row() {
        ForEach(this.params.actions, (action: DialogFormAction) => {
          Button(action.text)
            .fontColor($r('app.color.app_primary'))
            .backgroundColor(Color.Transparent)
            .margin({ left: 8 })
            .onClick((): void => {
              this.params.onSubmit(action.key, this.values);
            })
        }, (action: DialogFormAction): string => action.key)
        if (this.params.cancelText.length > 0) {
          Button(this.params.cancelText)
            .fontColor($r('app.color.app_primary'))
            .backgroundColor(Color.Transparent)
            .margin({ left: 8 })
            .onClick((): void => {
              this.params.onCancel();
            })
        }
      }
      .width('100%')
      .justifyContent(FlexAlign.End)
      .margin({ top: 16 })
    }
    .width('100%')
    .padding(24)
  }
}

/**
 * form 对话框的 builder。
 *
 * openCustomDialog 的 builder 只接受 CustomBuilder (无参), 字段参数与回调经闭包带入
 * (builder: () => { dialogFormBuilder(params) })。
 */
@Builder
function dialogFormBuilder(params: DialogFormParams) {
  DialogFormContent({ params: params });
}

/** form 请求: 解析 payload → 弹自绘多字段对话框 → 回 action+values / cancelled。 */
function formAction(payloadJson: string): Promise<BridgeResponse> {
  let payload: DialogFormPayload;
  try {
    payload = JSON.parse(payloadJson.length > 0 ? payloadJson : '{}') as DialogFormPayload;
  } catch (e) {
    console.error('[SysDialogBridge] parse form payload failed: ' + payloadJson);
    return Promise.resolve({ ok: false, error: 'parse payload failed' } as BridgeResponse);
  }
  return showForm(payload);
}

/**
 * 弹多字段输入对话框, 回 { ok:true, action, values } 或 { ok:true, cancelled:true }。
 *
 * 打开/关闭走同一 UIContext 的 PromptAction (openCustomDialog / closeCustomDialog, API 12):
 * 系统提示对话框 (showDialog) 只有标题/正文/按钮, 输入框只能自绘。
 * 结果只回传一次: 用户提交/取消置 settled, 之后到达的遮罩关闭与 dialogId 都不会二次回传。
 */
function showForm(payload: DialogFormPayload): Promise<DialogResult> {
  return new Promise((resolve, reject) => {
    const context = uiContext;
    if (context == null) {
      console.error('[SysDialogBridge] form failed: uiContext 未注入 (Index.ets 未调用 initSysDialogBridgeUiContext)');
      resolve({ ok: true, cancelled: true } as DialogResult);
      return;
    }
    const fields: DialogFormField[] = payload.fields ?? [];
    // dialogId 要等 openCustomDialog 的 Promise 回来才有; 用户极快点击时先置 settled, 回来后再补关
    const state: FormDialogState = { dialogId: -1, settled: false };
    const closeDialog = (): void => {
      if (state.dialogId < 0) {
        return;
      }
      try {
        context.getPromptAction().closeCustomDialog(state.dialogId);
      } catch (e) {
        console.warn('[SysDialogBridge] closeCustomDialog failed: ' + JSON.stringify(e));
      }
    };
    const finish = (result: DialogResult): void => {
      if (state.settled) {
        return;
      }
      state.settled = true;
      closeDialog();
      resolve(result);
    };
    const params: DialogFormParams = {
      title: payload.title ?? '',
      message: payload.message ?? '',
      fields: fields,
      actions: payload.actions ?? [],
      cancelText: payload.cancelText ?? '',
      onSubmit: (actionKey: string, values: string[]): void => {
        const valuesMap: Record<string, string> = {};
        fields.forEach((field: DialogFormField, index: number): void => {
          valuesMap[field.key] = values[index] ?? '';
        });
        finish({ ok: true, action: actionKey, values: valuesMap } as DialogResult);
      },
      onCancel: (): void => {
        finish({ ok: true, cancelled: true } as DialogResult);
      }
    };
    const options: promptAction.CustomDialogOptions = {
      builder: (): void => {
        dialogFormBuilder(params);
      },
      autoCancel: true,
      onWillDismiss: (action: DismissDialogAction): void => {
        // 拍遮罩/返回键关闭 = 未选择 (与 select/confirm 的取消语义一致)
        action.dismiss();
        finish({ ok: true, cancelled: true } as DialogResult);
      }
    };
    try {
      context.getPromptAction().openCustomDialog(options)
        .then((dialogId: number): void => {
          state.dialogId = dialogId;
          if (state.settled) {
            closeDialog();
          }
        })
        .catch((err: Error): void => {
          const reason: string = 'open dialog failed: ' + (err.message ?? String(err));
          console.error('[SysDialogBridge] ' + reason);
          reject(new Error(reason));
        });
    } catch (e) {
      console.error('[SysDialogBridge] form failed: ' + JSON.stringify(e));
      reject(new Error('open dialog failed'));
    }
  });
}
