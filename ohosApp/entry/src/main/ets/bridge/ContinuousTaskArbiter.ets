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
 * 系统取消长时任务 (用户删除长时任务通知、系统一致性校验不通过等) 时按接入规范**不立即再次申请**:
 * 音频记 [audioSuppressed] (下一次 acquireAudioTask, 即用户重新播放时解除), 后台作业把该 owner
 * 记入 [workSuppressed] (该业务 release 后解除, 即下一轮业务可再申请)。同时把取消事件回送 KMP
 * (type="continuousTaskCancel", 带被取消的 owner 业务通知 id), 让 Web 服务那种"靠长时任务存活"
 * 的业务能停掉自己。
 *
 * # 数据传输 (dataTransfer) 的实况窗进度
 * 长时任务通知由系统创建且内容不可修改, 唯一例外是 DATA_TRANSFER: 平台要求应用持续更新其进度,
 * 且"更新进度的通知类型必须为实况窗" (长时任务文档), 应用只能用系统代理创建的实况窗 id 更新内容
 * (SDK: LIVE_VIEW 槽位 / NOTIFICATION_CONTENT_SYSTEM_LIVE_VIEW 类型"第三方应用不能直接创建,
 * 只能在系统代理创建后用同一 ID 更新"). 故本模块记住 startBackgroundRunning 返回的 notificationId,
 * 由 [publishTransferProgress] 写进度 (typeCode=8 上传下载, downloadTemplate 进度模板)。
 */

import backgroundTaskManager from '@ohos.resourceschedule.backgroundTaskManager';
import notificationManager from '@ohos.notificationManager';
import common from '@ohos.app.ability.common';
import type { WantAgent } from '@ohos.app.ability.wantAgent';
import { ContinuousTaskCancelEvent } from './PlatformEventBridge';
import { emitPlatformEvent } from './PlatformEventBridge';

/** 音频/朗读的长时任务类型 (module.json5 的 backgroundModes 项)。 */
const MODE_AUDIO = 'audioPlayback';

/** 数据传输的长时任务类型 (缓存书籍那种持续的下载)。 */
const MODE_DATA_TRANSFER = 'dataTransfer';

/** 数据传输实况窗的 typeCode: 上传下载固定为 8 (平台仅支持此类型)。 */
const LIVE_VIEW_TYPE_CODE_DATA_TRANSFER = 8;

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

async function releaseSystemTask(context: common.UIAbilityContext): Promise<void> {
  try {
    await backgroundTaskManager.stopBackgroundRunning(context);
    console.info('[Legado] continuous task released');
  } catch (e) {
    console.error('[Legado] release continuous task failed: ' + JSON.stringify(e));
  } finally {
    transferNotificationId = 0;
  }
}

/** 按"音频 > 后台作业"重算一次系统槽位归属 (串行执行)。 */
async function reconcile(): Promise<void> {
  const context = contextRef;
  if (context == undefined) {
    return;
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
  await schedule();
}

/**
 * 系统取消了长时任务 (用户删除长时任务通知 / 系统一致性校验不通过): 清持有态 + 抑制立即重申请,
 * 并把取消事件回送 KMP (带被取消方的业务通知 id, 供 Web 服务那种业务停掉自己)。
 *
 * @param reason backgroundTaskManager.ContinuousTaskCancelReason 原值
 */
export function notifySystemCancelled(reason: number): void {
  const cancelled: TaskHolder | undefined = current;
  current = undefined;
  transferNotificationId = 0;
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
}

/**
 * 把数据传输进度写进系统代理创建的那条实况窗通知 (长时任务通知)。
 *
 * 仅当本进程持有 dataTransfer 长时任务时生效; 未持有 (未申请/已让给音频/被系统取消) 时 no-op。
 * 平台要求数据传输长时任务持续更新进度, 且必须是实况窗类型 (见文件头说明)。
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
  const request: notificationManager.NotificationRequest = {
    id: transferNotificationId,
    notificationSlotType: notificationManager.SlotType.LIVE_VIEW,
    content: {
      notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_SYSTEM_LIVE_VIEW,
      systemLiveView: {
        typeCode: LIVE_VIEW_TYPE_CODE_DATA_TRANSFER,
        title: title,
        text: text
      }
    },
    template: {
      name: 'downloadTemplate',
      data: {
        title: title,
        fileName: text,
        progressValue: progressValue
      }
    }
  };
  try {
    await notificationManager.publish(request);
    console.info('[Legado] transfer progress updated, id=' + transferNotificationId +
      ', value=' + progressValue);
  } catch (e) {
    console.error('[Legado] transfer progress update failed: ' + JSON.stringify(e));
  }
}
