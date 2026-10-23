import { KRAny, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { GycLiveClient, NativeLiveSession, LiveNativeEvent } from './GycLiveClient';
import { ErrorInfo } from '@tencentcloud/atomicxcore';
import { ComponentContent } from '@ohos.arkui.node';
import { createGycLiveView } from './LiveComponent';

interface Command {
  generation?: number; liveId?: string; count?: number; message?: string; requestId?: string;
  success?: boolean; accepted?: boolean; event?: KRAny;
}
interface Result { success: boolean; code?: number; message?: string; liveName?: string;
  notice?: string; ownerId?: string; ownerName?: string; ownerAvatarUrl?: string; }
class PendingReply {
  timer: number = -1;
  readonly callback: KuiklyRenderCallback;
  constructor(callback: KuiklyRenderCallback) { this.callback = callback; }
}

/** 与现有 LiveVideo 协议同名注册；公共状态机在 Kotlin，原生节点只传输与渲染。 */
@Observed
export class GycLiveView extends KuiklyRenderBaseView {
  static readonly VIEW_NAME: string = 'GycLiveView';
  private static nextViewId: number = 0;
  readonly surfaceViewId: string = `GycLiveVideo_${++GycLiveView.nextViewId}`;
  session: NativeLiveSession | null = null;
  private surfaceReady: boolean = false;
  private room: string = '';
  private nativeCallback: KuiklyRenderCallback | null = null;
  private publicCallback: KuiklyRenderCallback | null = null;
  private attached: boolean = false;
  private destroyed: boolean = false;
  private stopReady: (() => void) | null = null;
  private requestId: number = 0;
  private pending: Map<string, PendingReply> = new Map();
  private publishedGeneration: number = -1;

  createArkUIView(): ComponentContent<KuiklyRenderBaseView> {
    return new ComponentContent<KuiklyRenderBaseView>(this.getUIContext() as UIContext,
      wrapBuilder<[KuiklyRenderBaseView]>(createGycLiveView), this);
  }

  setProp(key: string, value: KRAny | KuiklyRenderCallback): boolean {
    if (this.destroyed) return true;
    if (key === 'nativeLiveEvent') { this.nativeCallback = value as KuiklyRenderCallback; this.configure(); return true; }
    if (key === 'liveEvent') { this.publicCallback = value as KuiklyRenderCallback; return true; }
    if (key === 'room') { this.room = typeof value === 'string' ? value : ''; this.configure(); return true; }
    return super.setProp(key, value);
  }
  private configure(): void {
    if (!this.room || !this.nativeCallback) return;
    this.nativeCallback({ type: 'configuration', room: this.room, visible: this.attached && GycLiveClient.isForeground(), ready: GycLiveClient.isReady() });
  }
  /** Ability 前后台与组件显隐均调用；完整观看退房后才允许恢复，预览离屏立即停止。 */
  setVisible(visible: boolean): void {
    if (this.destroyed) return;
    this.attached = visible;
    this.nativeCallback?.({ type: 'visibility', visible: visible && GycLiveClient.isForeground(), ready: GycLiveClient.isReady() });
  }
  attach(): void {
    if (!this.stopReady) this.stopReady = GycLiveClient.observeReady(() => {
      this.nativeCallback?.({ type: 'visibility', visible: this.attached && GycLiveClient.isForeground(), ready: GycLiveClient.isReady() });
    });
    this.setVisible(true);
    this.configure();
  }
  private emit(event: LiveNativeEvent): void { if (!this.destroyed) this.nativeCallback?.(event); }
  setSurfaceReady(viewId: string, ready: boolean): void {
    if (this.destroyed || viewId !== this.surfaceViewId) return;
    this.surfaceReady = ready;
    this.session?.setSurfaceView(ready ? viewId : '');
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    if (this.destroyed) { callback?.({ success: false, message: 'View released' }); return null; }
    let value: Command;
    try { value = JSON.parse(typeof params === 'string' ? params : '{}') as Command; }
    catch (_) { callback?.({ success: false, message: 'Invalid command' }); return null; }
    if (method === 'publish') {
      if (typeof value.generation === 'number' && value.generation >= this.publishedGeneration) {
        this.publishedGeneration = value.generation;
        if (value.event) this.publicCallback?.(value.event);
      }
      return null;
    }
    if (method === 'complete') {
      const id = value.requestId || '';
      const done = this.pending.get(id);
      this.pending.delete(id);
      if (done) { clearTimeout(done.timer); done.callback({ success: value.success === true, accepted: value.accepted === true, message: value.message || '' }); }
      return null;
    }
    if (method === 'sdkJoin' || method === 'sdkPreview') {
      const generation = value.generation;
      const id = value.liveId;
      if (!Number.isSafeInteger(generation) || generation! < this.publishedGeneration || typeof id !== 'string' || !id.trim() ||
        !this.attached || !GycLiveClient.isForeground() || !GycLiveClient.isReady()) {
        callback?.({ success: false, message: 'Not ready' }); return null;
      }
      const old = this.session;
      const session = new NativeLiveSession(id, generation!, method === 'sdkPreview', event => this.emit(event));
      this.session = session;
      if (this.surfaceReady) session.setSurfaceView(this.surfaceViewId);
      if (old) GycLiveClient.leave(old).catch(() => {});
      GycLiveClient.enter(session).then(() => {
        if (this.session !== session || this.destroyed || session.closed) throw new Error('Session released');
        const info = session.info;
        const result: Result = { success: true, liveName: info?.liveName || '', notice: info?.notice || '',
          ownerId: info?.liveOwner.userID || '', ownerName: info?.liveOwner.userName || '', ownerAvatarUrl: info?.liveOwner.avatarURL || '' };
        callback?.(result);
      }).catch((error: Error) => {
        const code = (error as ErrorInfo).code;
        callback?.({ success: false, code: typeof code === 'number' ? code : -1, message: error.message || 'Join failed' });
      });
      return null;
    }
    const session = this.session;
    if (method.startsWith('sdk')) {
      if (!session || session.generation !== value.generation) { callback?.({ success: false, message: 'Stale session' }); return null; }
      if (method === 'sdkLeave' || method === 'sdkStopPreview') {
        this.session = null;
        GycLiveClient.leave(session).then(() => callback?.({ success: true }))
          .catch((error: Error) => callback?.({ success: false, code: -1, message: error.message }));
      } else if (method === 'sdkObserve') session.observe();
      else if (method === 'sdkSendBarrage' || method === 'sdkLike') {
        const result = method === 'sdkSendBarrage' ? session.sendBarrage(value.message || '') : session.sendLike(value.count || 0);
        result.then(() => callback?.({ success: true })).catch((error: Error) => callback?.({ success: false, message: error.message }));
      } else callback?.({ success: false, message: 'Unsupported' });
      return null;
    }
    if (!this.nativeCallback) { callback?.({ success: false, accepted: false, message: 'Kotlin controller is not installed' }); return null; }
    if (this.pending.size >= 32) { callback?.({ success: false, accepted: false, message: 'Too many pending live commands' }); return null; }
    const id = `${++this.requestId}`;
    if (callback) {
      const pending = new PendingReply(callback);
      this.pending.set(id, pending);
      pending.timer = setTimeout(() => {
        if (this.pending.get(id) !== pending) return;
        this.pending.delete(id);
        pending.callback({ success: false, accepted: false, message: 'Live command timed out' });
      }, 20000);
    }
    this.nativeCallback({ type: 'command', method: method, requestId: id, message: value.message || '' });
    return null;
  }
  onDestroy(): void {
    if (this.destroyed) return;
    this.nativeCallback?.({ type: 'destroyed' });
    this.destroyed = true;
    this.surfaceReady = false;
    this.stopReady?.(); this.stopReady = null;
    const session = this.session;
    this.session = null;
    if (session) GycLiveClient.leave(session).catch(() => {});
    this.pending.forEach(done => { clearTimeout(done.timer); done.callback({ success: false, accepted: false, message: 'View released' }); });
    this.pending.clear();
    this.nativeCallback = null; this.publicCallback = null;
    super.onDestroy();
  }
}
