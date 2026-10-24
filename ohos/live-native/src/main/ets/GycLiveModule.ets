import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { GycLiveClient } from './GycLiveClient';
import { V2TIMManager, V2TIMSDKListener, V2TIMGroupListener } from '@tencentcloud/imsdk';

interface Input {
  id?: string; groups?: string[]; privacyGranted?: boolean; sdkAppId?: number; userId?: string; userSig?: string;
  nickname?: string; avatarUrl?: string; allowed?: boolean;
}
interface Failure { code?: number; }

/** 每个 Renderer 的账号、原始 IM 与 PiP transport；复用进程 SDK，不解释业务消息。 */
export class GycLiveModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME: string = 'GycLiveSdk';
  private readonly context: common.UIAbilityContext;
  private destroyed: boolean = false;
  private observers: Map<string, () => void> = new Map();
  private pipObservers: Map<string, () => void> = new Map();

  constructor(context: common.UIAbilityContext) {
    super(); this.context = context;
  }
  syncMode(): boolean { return false; }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    if (this.destroyed) { callback?.({ success: false, message: 'Live host released' }); return null; }
    let input: Input;
    try { input = JSON.parse(typeof params === 'string' ? params : '{}') as Input; }
    catch (_) { callback?.({ success: false, message: 'Invalid live command' }); return null; }
    if (input === null || typeof input !== 'object') { callback?.({ success: false, message: 'Invalid live command' }); return null; }
    if (method === 'listen') {
      if (input.id && callback) this.listen(input.id, Array.isArray(input.groups) ? input.groups : [], callback);
      return null;
    }
    if (method === 'unlisten') {
      const id = input.id || '';
      this.stopObserver(id);
      return null;
    }
    if (method === 'listenPiP') {
      const id = input.id || '';
      if (id && callback) {
        this.pipObservers.get(id)?.();
        let active = true;
        const changed = callback;
        const stop = GycLiveClient.observePictureInPicture(
          enabled => { if (active && !this.destroyed) changed({ type: 'changed', enabled }); },
          () => { if (active && !this.destroyed) changed({ type: 'closed' }); });
        this.pipObservers.set(id, () => { active = false; stop(); });
      }
      return null;
    }
    if (method === 'unlistenPiP') {
      const id = input.id || '';
      this.pipObservers.get(id)?.();
      this.pipObservers.delete(id);
      return null;
    }
    this.perform(method, input).then(() => {
      if (!this.destroyed) callback?.({ success: true });
    }).catch((error: Error) => {
      const code = (error as Failure).code;
      // 只记录组件的固定拒绝原因，供应商错误可能含账号或 UserSig。
      const reason = ['Invalid live identity', 'Login superseded',
        'Previous leave failed; logout the owning SDK before login',
        'Foreign IM identity or unknown SDKAppId; logout its owner first',
        'SDK identity does not match', 'SDK cleanup failed', 'Privacy consent required']
        .find(value => value === error.message) || 'SDK operation rejected';
      hilog.warn(0x30, 'GycLiveSdk', 'Live command failed: code=%{public}d, reason=%{public}s',
        typeof code === 'number' ? code : -1, reason);
      if (!this.destroyed) callback?.({ success: false, code: typeof code === 'number' ? code : -1,
        message: 'Live SDK operation failed' });
    });
    return null;
  }

  private async perform(method: string, input: Input): Promise<void> {
    if (method === 'login') {
      if (input.privacyGranted !== true) throw new Error('Privacy consent required');
      await GycLiveClient.login(input.sdkAppId || 0, input.userId || '', input.userSig || '', this.context,
        input.nickname || '', input.avatarUrl || '');
    } else if (method === 'logout') {
      await GycLiveClient.logout();
      hilog.info(0x30, 'GycLiveSdk', '%{public}s', 'Live SDK logout completed');
    } else if (method === 'restorePiP') {
      await GycLiveClient.restorePictureInPicture();
    } else throw new Error('Unsupported live command');
  }

  private listen(id: string, groups: string[], callback: KuiklyRenderCallback): void {
    this.stopObserver(id);
    if (!GycLiveClient.isReady()) { callback({ type: 'failed' }); return; }
    const allowedGroups = new Set(groups.filter(group => typeof group === 'string' && group.trim().length > 0));
    if (allowedGroups.size === 0) { callback({ type: 'failed' }); return; }
    const sdk = V2TIMManager.getInstance();
    const user = sdk.getLoginUser();
    if (!user) { callback({ type: 'failed' }); return; }
    let active = true;
    const emit = (event: KRAny): void => {
      if (active && !this.destroyed && sdk.getLoginUser() === user) callback(event);
    };
    const group: V2TIMGroupListener = {
      onReceiveRESTCustomData: (groupId, bytes) => {
        if (!allowedGroups.has(groupId)) return;
        try {
          const payload = util.TextDecoder.create('utf-8').decodeWithStream(new Uint8Array(bytes));
          emit({ type: 'data', groupId, payload });
        } catch (_) {}
      },
      onMemberKicked: (groupId, operator, members) => {
        if (allowedGroups.has(groupId) && members.some(member => member.userID === user)) emit({ type: 'removed', groupId, operatorUserId: operator.userID });
      },
      onGroupDismissed: (groupId) => { if (allowedGroups.has(groupId)) emit({ type: 'dismissed', groupId }); },
      onGroupRecycled: (groupId) => { if (allowedGroups.has(groupId)) emit({ type: 'dismissed', groupId }); }
    };
    const account: V2TIMSDKListener = {
      onKickedOffline: () => { if (active && !this.destroyed && (!sdk.getLoginUser() || sdk.getLoginUser() === user)) callback({ type: 'kickedOffline' }); },
      onUserSigExpired: () => { if (active && !this.destroyed && (!sdk.getLoginUser() || sdk.getLoginUser() === user)) callback({ type: 'expired' }); }
    };
    const stop = (): void => {
      active = false;
      // 分别撤销，不移除组件或其他页面拥有的 SDK 监听器。
      try { sdk.removeGroupListener(group); } finally { sdk.removeSDKListener(account); }
    };
    this.observers.set(id, stop);
    try { sdk.addGroupListener(group); sdk.addSDKListener(account); }
    catch (_) { this.stopObserver(id); callback({ type: 'failed' }); }
  }

  private stopObserver(id: string): void {
    const stop = this.observers.get(id);
    this.observers.delete(id);
    try { stop?.(); } catch (_) {}
  }

  onDestroy(): void {
    if (this.destroyed) return;
    this.destroyed = true;
    this.pipObservers.forEach(stop => { try { stop(); } catch (_) {} }); this.pipObservers.clear();
    this.observers.forEach(stop => { try { stop(); } catch (_) {} }); this.observers.clear();
    // Renderer 关闭不注销进程账号；根会话 reset 或 Ability 销毁负责 logout。
    super.onDestroy();
  }
}
