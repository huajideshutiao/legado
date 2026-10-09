/**
 * 长时任务仲裁 (ArkTS 侧唯一一处申请/释放 长时任务的地方)。
 *
 * # 为什么要仲裁
 * 1. **数量**: 一个 UIAbility 在 API<=20 同时只允许一条长时任务 (API 21 起才放宽到 10 条,
 *    本模块 compatibleSdkVersion 为 12, 不能依赖), 两条业务各自直接申请时, 后申请的那条直接失败。
 * 2. **优先级**: 音频播放/朗读必须绝对优先 —— 不允许出现"因为导出/缓存/校验/更新书籍/Web 服务
 *    占着槽位, 音频保活失败"的情况。
 *
 * 故所有申请方都经本模块: 音频 ([acquireAudioTask], 由 MediaBridgeHandler 的
 * setBackgroundTask 调用) 高于后台作业 ([acquireWorkTask], 由 EntryAbility 的通知回调按
 * 业务通知的 continuousTask 字段调用)。音频申请时先把占槽的后台作业释放掉 (让位), 音频结束后
 * 再把仍在期望中的后台作业重新申请回来; 后台作业在音频播放期间不申请 (降级为只发业务通知)。
 * **让位只影响"保活", 业务本身与业务通知都不动** (调用方各自的状态机不感知这件事)。
 *
 * # 系统取消
 * 系统挂起/取消长时任务 (用户删除长时任务通知、原因码判定不合规等) 时按接入规范**不立即再次申请**:
 * 音频记 [audioSuppressed] (下一次 acquireAudioTask, 即用户重新播放时解除), 后台作业把该 owner
 * 记入 [workSuppressed] (该业务 release 后解除, 即下一轮业务可再申请)。同时把事件回送 KMP
 * (type="continuousTaskCancel", 带被取消的 owner 业务通知 id)。
 *
 * 回调不是可信的"保活没了"信号: 只有"移除通知"与"冻结"两类原因会回传, 其余 (低速、一致性管控等)
 * 不回传。故业务不得靠它决定去留 —— 本模块在进后台 ([verifyWorkTasks]) 与实况窗心跳时按系统真实
 * 状态自检 (API 20 起可查), 发现本地持有态与系统不一致就纠正并按业务期望重新申请。
 *
 * # 数据传输 (dataTransfer) 的实况窗进度
 * 长时任务通知由系统创建且内容不可修改, 唯一例外是 DATA_TRANSFER: 平台要求应用持续更新其进度,
 * 且"更新进度的通知类型必须为实况窗" (长时任务文档), 应用只能用系统代理创建的实况窗 id 更新内容
 * (SDK: LIVE_VIEW 槽位 / NOTIFICATION_CONTENT_SYSTEM_LIVE_VIEW 类型"第三方应用不能直接创建,
 * 只能在系统代理创建后用同一 ID 更新"). 故本模块记住 startBackgroundRunning 返回的 notificationId,
 * 由 [publishTransferProgress] 写进度 (typeCode=8 上传下载, downloadTemplate 进度模板)。
 *
 * 三方应用更新时系统只采纳 template (systemLiveView 里的文案会被丢弃), 实况窗显示的文案只能放
 * downloadTemplate.data 的 title/fileName —— SDK 标注两者必填, 缺任一模板校验不过;
 * systemLiveView 只留 typeCode, 供"槽位 LIVE_VIEW + 系统实况窗内容类型"这条判定。
 *
 * 系统时限: 实况窗首次发布后 10 分钟没有一次更新就删通知停任务, 之后每次更新把时限重置为 15 分钟
 * (进度到 100 时为 30 分钟)。故本模块在保活期内心跳式重发模板 (进度没变也照发), 业务收尾时先写
 * 100 再释放。
 */

import backgroundTaskManager from '@ohos.resourceschedule.backgroundTaskManager';
import notificationManager from '@ohos.notificationManager';
import common from '@ohos.app.ability.common';
import deviceInfo from '@ohos.deviceInfo';
import type { WantAgent } from '@ohos.app.ability.wantAgent';
import { ContinuousTaskCancelEvent } from './PlatformEventBridge';
import { emitPlatformEvent } from './PlatformEventBridge';

/** 音频/朗读的长时任务类型 (module.json5 的 backgroundModes 项)。 */
const MODE_AUDIO = 'audioPlayback';

/** 数据传输的长时任务类型 (缓存书籍那种持续的下载)。 */
const MODE_DATA_TRANSFER = 'dataTransfer';

/** 数据传输实况窗的 typeCode: 上传下载固定为 8 (平台仅支持此类型)。 */
const LIVE_VIEW_TYPE_CODE_DATA_TRANSFER = 8;

/**
 * 实况窗心跳间隔: 系统时限是"首次发布后 10 分钟无更新即删通知停任务, 之后每次更新把时限重置为
 * 15 分钟", 心跳统一取 8 分钟 (小于两个时限, 进度没变也照发同一条模板)。
 */
const TRANSFER_HEARTBEAT_MS = 8 * 60 * 1000;

/** 实况窗 downloadTemplate 的三项数据 (title/fileName 为平台必填, progressValue 取 0-100)。 */
interface TransferTemplate {
  title: string;
  fileName: string;
  progressValue: number;
}

/** 音频 hold 的 owner 名 (业务 owner 一律用业务通知 id 字符串)。 */
const OWNER_AUDIO = 'audio';

const KIND_AUDIO = 0;
const KIND_WORK = 1;

/** 一个申请方 (音频或某个后台作业)。 */
interface TaskHolder {
  owner: string;
  kind: number;
  mode: string;
  wantAgent: WantAgent;
}

/** 最近一次申请时拿到的 UIAbility context (音频结束后恢复后台作业时还要用)。 */
let contextRef: common.UIAbilityContext | undefined = undefined;

/** 当前真正持有系统槽位的申请方 (undefined = 当前没有长时任务)。 */
let current: TaskHolder | undefined = undefined;

/** 音频是否想占槽 (正在播放/朗读即 true); 与是否持有分开, 系统取消后会"想但不持有"。 */
let audioDesired: boolean = false;
let audioWantAgent: WantAgent | undefined = undefined;
/** 音频被系统取消过: 不再自动重申请, 由下一次 acquireAudioTask (用户重新播放) 解除。 */
let audioSuppressed: boolean = false;

/** 期望占槽的后台作业 (插入顺序 = 先到先得; 同时只会有第一条真正拿到槽位)。 */
let workWanted: TaskHolder[] = [];
/** 被系统取消过、本轮业务内不再重申请的后台作业 owner。 */
let workSuppressed: string[] = [];

/** 数据传输长时任务通知 id (系统代理创建的实况窗; 0 = 数据传输长时任务不在手)。 */
let transferNotificationId: number = 0;

/** 实况窗最近一次写入的文案与进度 (心跳重发用; undefined = 本次保活期内还没写过)。 */
let transferTemplate: TransferTemplate | undefined = undefined;

/** 实况窗心跳定时器。 */
let transferHeartbeat: number | undefined = undefined;

/** 下次 reconcile 是否按系统真实状态自检持有态 (由 [verifyWorkTasks] 与心跳置位, 平时不查)。 */
let verifyPending: boolean = false;

/** 串行化队列: 申请/释放各来自不同调用点, 逐次 reconcile 避免交叉申请。 */
let queueTail: Promise<void> = Promise.resolve();

function schedule(): Promise<void> {
  const next: Promise<void> = queueTail.then((): Promise<void> => reconcile());
  // 单次失败不能断掉后续的队列 (reconcile 内部已各自 catch)
  queueTail = next.catch((): void => {});
  return next;
}

function findWork(owner: string): TaskHolder | undefined {
  return workWanted.find((item: TaskHolder): boolean => item.owner === owner);
}

function removeWork(owner: string): void {
  workWanted = workWanted.filter((item: TaskHolder): boolean => item.owner !== owner);
}

function removeSuppressed(owner: string): void {
  workSuppressed = workSuppressed.filter((item: string): boolean => item !== owner);
}

/** 取消实况窗心跳。 */
function clearTransferHeartbeat(): void {
  if (transferHeartbeat != undefined) {
    clearTimeout(transferHeartbeat);
    transferHeartbeat = undefined;
  }
}

/** 该业务 owner 是否正是当前持着 dataTransfer 长时任务的那个 (收尾时要先把实况窗写到 100)。 */
export function isTransferHolderOwner(owner: string): boolean {
  return current != undefined && current.mode === MODE_DATA_TRANSFER && current.owner === owner;
}

async function releaseSystemTask(context: common.UIAbilityContext): Promise<void> {
  try {
    await backgroundTaskManager.stopBackgroundRunning(context);
    console.info('[Legado] continuous task released');
  } catch (e) {
    console.error('[Legado] release continuous task failed: ' + JSON.stringify(e));
  } finally {
    transferNotificationId = 0;
    clearTransferHeartbeat();
  }
}

/** 按"音频 > 后台作业"重算一次系统槽位归属 (串行执行)。 */
async function reconcile(): Promise<void> {
  const context = contextRef;
  if (context == undefined) {
    return;
  }

  // 0) 按系统真实状态自检本地持有态 (只在 [verifyWorkTasks] / 心跳要求时查):
  //    系统可能在没有任何回调的情况下挂起/取消长时任务, 查不到就把本地状态纠正过来,
  //    由下面的逻辑按业务期望重新申请
  if (verifyPending && current != undefined) {
    verifyPending = false;
    const holder: TaskHolder = current;
    const held: boolean | undefined = await verifyHeldTask(context, holder);
    if (held === false) {
      console.info('[Legado] continuous task missing in system, owner=' + holder.owner);
      transferNotificationId = 0;
      clearTransferHeartbeat();
      current = undefined;
    }
  }

  // 1) 音频绝对优先: 想占就必须尝试拿到, 先把占槽的后台作业让出去
  if (audioDesired && !audioSuppressed) {
    if (current != undefined && current.kind === KIND_AUDIO) {
      return;
    }
    if (current != undefined) {
      const yielded: TaskHolder = current;
      await releaseSystemTask(context);
      current = undefined;
      console.info('[Legado] background task yielded to audio, owner=' + yielded.owner);
    }
    const wantAgentObj: WantAgent | undefined = audioWantAgent;
    if (wantAgentObj != undefined) {
      try {
        await backgroundTaskManager.startBackgroundRunning(context, [MODE_AUDIO], wantAgentObj);
        current = { owner: OWNER_AUDIO, kind: KIND_AUDIO, mode: MODE_AUDIO, wantAgent: wantAgentObj };
        console.info('[Legado] audio continuous task started');
        return;
      } catch (e) {
        // 音频没拿到槽位 (无权限/已有其它任务/类型校验不过等): 只记日志并继续往下,
        // 让刚让位的后台作业把槽位拿回去 —— 不能因为音频申请失败把两条保活都丢掉
        console.error('[Legado] audio continuous task failed: ' + JSON.stringify(e));
      }
    }
  } else if (audioDesired) {
    // 音频被系统取消过 (抑制中): 不重申请, 后台作业也不占槽 (音频仍在播放, 优先级最高)
    return;
  }

  // 2) 后台作业: 业务已结束的先释放, 再按先到先得拿一个槽位
  if (current != undefined && current.kind === KIND_WORK && findWork(current.owner) == undefined) {
    await releaseSystemTask(context);
    current = undefined;
  }
  if (current != undefined) {
    return;
  }
  for (const holder of workWanted) {
    if (workSuppressed.find((item: string): boolean => item === holder.owner) != undefined) {
      continue;
    }
    try {
      const res: backgroundTaskManager.ContinuousTaskNotification =
        await backgroundTaskManager.startBackgroundRunning(context, [holder.mode], holder.wantAgent);
      current = holder;
      transferNotificationId = holder.mode === MODE_DATA_TRANSFER ? res.notificationId : 0;
      if (holder.mode === MODE_DATA_TRANSFER) {
        clearTransferHeartbeat();
        // 让位给音频后重新拿回时实况窗是新建的: 按上一次的模板补写一次 (业务下次进度照旧覆盖)
        const previous: TransferTemplate | undefined = transferTemplate;
        if (previous != undefined) {
          await publishTransferLiveView(previous);
          scheduleTransferHeartbeat();
        }
      }
      console.info('[Legado] background continuous task started, owner=' + holder.owner +
        ', mode=' + holder.mode + ', notificationId=' + transferNotificationId);
    } catch (e) {
      console.error('[Legado] background continuous task failed: ' + JSON.stringify(e) +
        ', owner=' + holder.owner + ', mode=' + holder.mode);
    }
    return;
  }
}

/**
 * 音频 (播放/朗读) 申请长时任务: 音频优先级最高, 会抢占后台作业。
 *
 * @returns true = 音频已持有长时任务 (调用方据此维护自己的"在保活"标志)
 */
export async function acquireAudioTask(
  context: common.UIAbilityContext, wantAgent: WantAgent
): Promise<boolean> {
  contextRef = context;
  audioDesired = true;
  audioWantAgent = wantAgent;
  // 新的申请 = 用户又开始播放: 解除"系统取消后不立即重申请"的抑制
  audioSuppressed = false;
  await schedule();
  return current != undefined && current.kind === KIND_AUDIO;
}

/** 音频释放长时任务 (播放/朗读退场); 若后台作业还在期望中, 此时把槽位交给它。 */
export async function releaseAudioTask(context: common.UIAbilityContext): Promise<void> {
  contextRef = context;
  audioDesired = false;
  audioWantAgent = undefined;
  await schedule();
}

/**
 * 后台作业申请长时任务 (降级语义: 音频在播放或已被系统取消时, 本调用只记录期望, 不申请)。
 *
 * @param owner 业务标识 (用该业务的业务通知 id 字符串, 便于取消事件回送时对上号)
 * @param mode module.json5 backgroundModes 的配置项
 */
export async function acquireWorkTask(
  context: common.UIAbilityContext, owner: string, mode: string, wantAgent: WantAgent
): Promise<void> {
  contextRef = context;
  const holder: TaskHolder = { owner: owner, kind: KIND_WORK, mode: mode, wantAgent: wantAgent };
  const existing: TaskHolder | undefined = findWork(owner);
  if (existing == undefined) {
    workWanted.push(holder);
  } else {
    workWanted = workWanted.map((item: TaskHolder): TaskHolder =>
      item.owner === owner ? holder : item);
  }
  await schedule();
}

/** 后台作业释放长时任务 (业务结束/通知被取消); 同时解除该业务的"系统取消后不重申请"抑制。 */
export async function releaseWorkTask(
  context: common.UIAbilityContext, owner: string
): Promise<void> {
  contextRef = context;
  removeWork(owner);
  removeSuppressed(owner);
  // 业务收尾: 持有实况窗的那个业务结束时, 实况窗随任务一起结束, 文案不再留着
  if (isTransferHolderOwner(owner)) {
    transferTemplate = undefined;
    clearTransferHeartbeat();
  }
  await schedule();
}

/**
 * 系统挂起/取消了长时任务 (原因码见 backgroundTaskManager.ContinuousTaskCancelReason): 清持有态 +
 * 抑制立即重申请, 并把事件回送 KMP (带被取消方的业务通知 id)。
 *
 * 只有"移除通知"与"冻结"两类原因会回传, 其余 (低速、一致性管控等) 不回传 —— 业务不得靠本回调
 * 决定自己的去留, 它只当提示: 保活由各业务在状态变化、进后台与心跳时重述并按状态自检。
 *
 * @param reason backgroundTaskManager.ContinuousTaskCancelReason 原值
 */
export function notifySystemCancelled(reason: number): void {
  const cancelled: TaskHolder | undefined = current;
  current = undefined;
  transferNotificationId = 0;
  transferTemplate = undefined;
  clearTransferHeartbeat();
  if (cancelled == undefined) {
    console.info('[Legado] continuous task cancelled by system (not held by us), reason=' + reason);
    return;
  }
  if (cancelled.kind === KIND_AUDIO) {
    audioSuppressed = true;
    console.info('[Legado] audio continuous task cancelled by system, reason=' + reason);
    return;
  }
  if (workSuppressed.find((item: string): boolean => item === cancelled.owner) == undefined) {
    workSuppressed.push(cancelled.owner);
  }
  console.info('[Legado] background continuous task cancelled by system, owner=' + cancelled.owner +
    ', reason=' + reason);
  const payload: ContinuousTaskCancelEvent = {
    type: 'continuousTaskCancel',
    reason: reason,
    notificationId: Number.parseInt(cancelled.owner, 10)
  };
  emitPlatformEvent(payload);
}

/** UIAbility 销毁: 长时任务随 Ability 一起结束, 清持有态 (期望态保留, 下次进度更新会重新申请)。 */
export function notifyAbilityDestroyed(): void {
  current = undefined;
  transferNotificationId = 0;
  transferTemplate = undefined;
  clearTransferHeartbeat();
}

/**
 * 把数据传输进度写进系统代理创建的那条实况窗通知 (长时任务通知)。
 *
 * 仅当本进程持有 dataTransfer 长时任务时生效; 未持有 (未申请/已让给音频/被系统取消) 时 no-op。
 * 文案与进度只经 downloadTemplate 下发 (三方应用更新时 systemLiveView 的文案会被丢弃),
 * 写入后按系统时限起心跳 (见文件头说明)。
 */
export async function publishTransferProgress(
  title: string, text: string, progress: number, max: number
): Promise<void> {
  if (transferNotificationId === 0) {
    return;
  }
  const progressValue: number = max > 0
    ? Math.max(0, Math.min(100, Math.round(progress / max * 100)))
    : 0;
  transferTemplate = { title: title, fileName: text, progressValue: progressValue };
  await publishTransferLiveView(transferTemplate);
  scheduleTransferHeartbeat();
}

/**
 * 业务正常收尾: 先把实况窗进度写到 100 (系统把时限放宽到 30 分钟, 收尾这段时间不会被判超时),
 * 再由调用方释放长时任务。仅当本进程持有 dataTransfer 长时任务时生效。
 */
export async function completeTransferProgress(): Promise<void> {
  const template = transferTemplate;
  if (transferNotificationId === 0 || template == undefined) {
    return;
  }
  const finished: TransferTemplate = {
    title: template.title,
    fileName: template.fileName,
    progressValue: 100
  };
  transferTemplate = finished;
  await publishTransferLiveView(finished);
}

/**
 * 按系统真实状态自检保活 (供 UIAbility 进后台时调用)。
 *
 * 不依赖长时任务取消回调 (它只回传部分原因): 本地记录的持有态与系统不一致时,
 * 由 [reconcile] 纠正并按业务期望重新申请。
 */
export async function verifyWorkTasks(context: common.UIAbilityContext): Promise<void> {
  contextRef = context;
  verifyPending = true;
  await schedule();
}

/**
 * 查系统里本应用真实持有的长时任务 (含被挂起的)。
 *
 * @returns true=本次记录的持有态仍在; false=系统里已没有该任务; undefined=无法判定 (API<20 或查询失败)
 */
async function verifyHeldTask(
  context: common.UIAbilityContext, holder: TaskHolder
): Promise<boolean | undefined> {
  if (deviceInfo.sdkApiVersion < 20) {
    return undefined;
  }
  try {
    const tasks: backgroundTaskManager.ContinuousTaskInfo[] =
      await backgroundTaskManager.getAllContinuousTasks(context, true);
    const mine: backgroundTaskManager.ContinuousTaskInfo | undefined =
      tasks.find((item: backgroundTaskManager.ContinuousTaskInfo): boolean =>
        item.backgroundModes.find((mode: string): boolean => mode === holder.mode) != undefined);
    if (mine == undefined) {
      return false;
    }
    if (mine.suspendState) {
      console.info('[Legado] continuous task suspended, owner=' + holder.owner);
    }
    return true;
  } catch (e) {
    console.error('[Legado] continuous task state query failed: ' + JSON.stringify(e));
    return undefined;
  }
}

/** 写一次实况窗 (模板数据由调用方给出)。 */
async function publishTransferLiveView(template: TransferTemplate): Promise<void> {
  if (transferNotificationId === 0) {
    return;
  }
  const request: notificationManager.NotificationRequest = {
    id: transferNotificationId,
    notificationSlotType: notificationManager.SlotType.LIVE_VIEW,
    content: {
      notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_SYSTEM_LIVE_VIEW,
      // 槽位 LIVE_VIEW + 系统实况窗内容类型 = 平台判定"这是系统代理创建的实况窗"的凭据;
      // 文案不进这里 (三方应用更新时 systemLiveView 的内容会被系统丢弃)
      systemLiveView: {
        typeCode: LIVE_VIEW_TYPE_CODE_DATA_TRANSFER
      }
    },
    template: {
      name: 'downloadTemplate',
      data: {
        // title 与 fileName 是 downloadTemplate 的必填项, 也是实况窗实际显示的文案
        title: template.title,
        fileName: template.fileName,
        progressValue: template.progressValue
      }
    }
  };
  try {
    await notificationManager.publish(request);
    console.info('[Legado] transfer live view updated, id=' + transferNotificationId +
      ', value=' + template.progressValue);
  } catch (e) {
    // 实况窗已不在 (被系统删掉/保活已丢) 时 publish 会失败: 记日志, 心跳与业务重述会再试
    console.error('[Legado] transfer live view update failed: ' + JSON.stringify(e));
  }
}

/**
 * 排一次实况窗心跳 (每次写入都重排, 等于把系统时限重置)。
 */
function scheduleTransferHeartbeat(): void {
  clearTransferHeartbeat();
  if (transferNotificationId === 0 || transferTemplate == undefined) {
    return;
  }
  transferHeartbeat = setTimeout((): void => {
    transferHeartbeat = undefined;
    transferHeartbeatTick().catch((e: Error): void => {
      console.error('[Legado] transfer heartbeat failed: ' + JSON.stringify(e));
    });
  }, TRANSFER_HEARTBEAT_MS);
}

/**
 * 心跳: 进度没变也重发一次模板 (续系统时限), 并在重发前按系统真实状态自检一次保活是否还在 ——
 * "下载停住"与"保活被系统悄悄收走"两种情况都不会让实况窗超时或被删。
 */
async function transferHeartbeatTick(): Promise<void> {
  const template = transferTemplate;
  if (transferNotificationId === 0 || template == undefined) {
    return;
  }
  // 自检: 槽位若已被系统收走, reconcile 会纠正本地状态并按业务期望重新申请 (拿到新实况窗 id)
  verifyPending = true;
  await schedule();
  if (transferNotificationId === 0) {
    // 自检后仍没有槽位 (重新申请失败/让位给音频): 保留模板, 业务下次重述或下轮心跳再试
    return;
  }
  // 重拿到槽位时实况窗 id 可能变了, 按同一模板重写一次保证内容与进度不丢
  transferTemplate = template;
  await publishTransferLiveView(template);
  scheduleTransferHeartbeat();
}
