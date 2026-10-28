import { KRAny, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { GycLiveClient, NativeLiveSession, LiveNativeEvent, LivePictureInPictureOwner } from './GycLiveClient';
import { AtomicLiveView, ErrorInfo } from '@tencentcloud/atomicxcore';
import { ComponentContent } from '@ohos.arkui.node';
import { createGycLiveView } from './LiveComponent';
import PiPWindow from '@ohos.PiPWindow';
import { common } from '@kit.AbilityKit';

interface Command {
  generation?: number; liveId?: string; count?: number; message?: string; requestId?: string;
  success?: boolean; accepted?: boolean; event?: KRAny; wideContent?: boolean;
  userId?: string; followed?: boolean;
}
interface Result { success: boolean; code?: number; message?: string; liveName?: string;
  notice?: string; ownerId?: string; ownerName?: string; ownerAvatarUrl?: string; userId?: string; }
export interface AtomicPlaybackViewParameters {
  view: AtomicLiveView;
  mixSurfaceController: XComponentController;
}
class PendingReply {
  timer: number = -1;
  readonly callback: KuiklyRenderCallback;
  constructor(callback: KuiklyRenderCallback) { this.callback = callback; }
}

/** 与现有 LiveVideo 协议同名注册；公共状态机在 Kotlin，原生节点只传输与渲染。 */
@Observed
export class GycLiveView extends KuiklyRenderBaseView implements LivePictureInPictureOwner {
  static readonly VIEW_NAME: string = 'GycLiveView';
  session: NativeLiveSession | null = null;
  readonly surfaceController: XComponentController = new XComponentController();
  pictureInPictureAspect: number = -1;
  private pipController: PiPWindow.PiPController | null = null;
  private pipSession: NativeLiveSession | null = null;
  private pipStarting: Promise<void> | null = null;
  private pipPhase: string = 'idle';
  private pictureInPictureHostManaged: boolean = false;
  private room: string = '';
  private roomClosed: boolean = false;
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
    if (key === 'pictureInPictureHostManaged') { this.pictureInPictureHostManaged = value === true; return true; }
    if (key === 'nativeLiveEvent') { this.nativeCallback = value as KuiklyRenderCallback; this.configure(); return true; }
    if (key === 'liveEvent') { this.publicCallback = value as KuiklyRenderCallback; return true; }
    if (key === 'room') { this.roomClosed = false; this.room = typeof value === 'string' ? value : ''; this.configure(); return true; }
    return super.setProp(key, value);
  }
  private configure(): void {
    if (!this.room || !this.nativeCallback) return;
    this.nativeCallback({ type: 'configuration', room: this.room, visible: this.playbackVisible(), ready: GycLiveClient.isReady() });
  }
  /** 完整观看保留已加入的房间；预览仍随前后台和显隐停止。 */
  setVisible(visible: boolean): void {
    if (this.destroyed) return;
    this.attached = visible;
    this.notifyVisibility();
  }
  attach(): void {
    if (!this.stopReady) {
      let foreground = this.isForeground();
      this.stopReady = GycLiveClient.observeReady(() => {
        const currentForeground = this.isForeground();
        const returned = !foreground && currentForeground;
        foreground = currentForeground;
        if (returned && !this.pictureInPictureHostManaged && this.pipPhase === 'started' && this.pipSession) {
          const session = this.pipSession;
          const controller = this.pipController;
          this.restorePictureInPicture().catch(() => {
            if (!this.pipCurrent(session) || this.pipController !== controller) return;
            this.roomClosed = true;
            this.emit({ type: 'playbackError', generation: session.generation, code: -1, message: 'Picture in picture restore failed' });
            GycLiveClient.leave(session).catch(() => {});
            this.notifyVisibility();
          });
        }
        this.notifyVisibility();
      });
    }
    this.setVisible(true);
    this.configure();
  }
  private isForeground(): boolean {
    return GycLiveClient.isForeground(this.getUIContext()?.getHostContext());
  }
  private playbackVisible(): boolean {
    const session = this.session;
    if (this.roomClosed || session?.closed) return false;
    return session !== null && !session.preview && GycLiveClient.isReady() || this.attached && this.isForeground();
  }
  private notifyVisibility(): void {
    // 系统可能先 STOPPED 再恢复前台或可见区域；二者都恢复后才撤销续播保护。
    if (this.pipPhase === 'restoring' && !this.pipController && this.attached && this.isForeground()) {
      this.pipPhase = 'idle'; this.pipSession = null; this.pictureInPictureAspect = -1;
      GycLiveClient.releasePictureInPicture(this);
    }
    this.nativeCallback?.({ type: 'visibility', visible: this.playbackVisible(), ready: GycLiveClient.isReady() });
  }
  private pipCurrent(session: NativeLiveSession): boolean {
    return !this.destroyed && !session.closed && this.session === session && this.pipSession === session;
  }
  private reportPictureInPicture(enabled: boolean, closed: boolean = false): void {
    const session = this.pipSession;
    if (session) this.emit({ type: 'pictureInPictureChanged', generation: session.generation, enabled: enabled });
    GycLiveClient.pictureInPictureChanged(this, enabled, closed);
  }
  private async enterPictureInPicture(wideContent: boolean): Promise<void> {
    const session = this.session;
    if (!session || session.closed || session.preview || !session.info || !this.playerSurfaceReady() ||
      !this.isForeground() || !PiPWindow.isPiPEnabled() || this.pipPhase !== 'idle' || this.pipStarting !== null ||
      !GycLiveClient.claimPictureInPicture(this)) throw new Error('Picture in picture is not ready');
    this.pipSession = session;
    this.pipPhase = 'starting';
    this.pictureInPictureAspect = wideContent ? 16 / 9 : 9 / 16;
    const starting = this.startPictureInPicture(session, wideContent);
    this.pipStarting = starting;
    try { await starting; }
    finally { if (this.pipStarting === starting) this.pipStarting = null; }
  }
  private async startPictureInPicture(session: NativeLiveSession, wideContent: boolean): Promise<void> {
    let controller: PiPWindow.PiPController | null = null;
    let startCalled = false;
    try {
      const context = this.getUIContext()?.getHostContext();
      if (!context) throw new Error('Window context is unavailable');
      controller = await PiPWindow.create({ context: context, componentController: this.surfaceController,
        templateType: PiPWindow.PiPTemplateType.VIDEO_LIVE,
        contentWidth: wideContent ? 1280 : 720, contentHeight: wideContent ? 720 : 1280 });
      if (!this.pipCurrent(session)) throw new Error('Session released');
      this.pipController = controller;
      const currentController = controller;
      controller.on('stateChange', (state: PiPWindow.PiPState, _reason: string) => {
        if (!this.pipCurrent(session) || this.pipController !== currentController) return;
        if (state === PiPWindow.PiPState.STARTED) {
          // 仅本次请求首次启动时退到桌面；恢复/重复通知不能再次最小化宿主。
          if (this.pipPhase === 'starting') {
            this.pipPhase = 'started';
            this.reportPictureInPicture(true);
            // 单 Ability 容器自己切换首页与直播；默认仍保留独立窗口的退后台行为。
            if (!this.pictureInPictureHostManaged) {
              this.moveToBackground(session, currentController, context as common.UIAbilityContext).catch(() => {});
            }
          }
        } else if (state === PiPWindow.PiPState.ABOUT_TO_STOP) {
          // 系统关闭尚未 STOPPED 时回前台，不能再以恢复名义重复 stopPiP。
          if (this.pipPhase !== 'restoring') this.pipPhase = 'stopping';
        } else if (state === PiPWindow.PiPState.ABOUT_TO_RESTORE) {
          this.pipPhase = 'restoring';
        } else if (state === PiPWindow.PiPState.STOPPED || state === PiPWindow.PiPState.ERROR) {
          const restoring = this.pipPhase === 'restoring' && state !== PiPWindow.PiPState.ERROR;
          this.pipPhase = restoring ? 'restoring' : 'idle';
          if (!restoring && state === PiPWindow.PiPState.STOPPED) this.roomClosed = true;
          this.reportPictureInPicture(false, !restoring && state === PiPWindow.PiPState.STOPPED);
          currentController.off('stateChange');
          this.pipController = null;
          if (!restoring) {
            this.pipSession = null; this.pictureInPictureAspect = -1; GycLiveClient.releasePictureInPicture(this);
            if (state === PiPWindow.PiPState.STOPPED) GycLiveClient.leave(session).catch(() => {});
          }
        }
        this.notifyVisibility();
      });
      controller.setAutoStartEnabled(false);
      startCalled = true;
      await controller.startPiP();
      if (!this.pipCurrent(session)) throw new Error('Session released');
    } catch (error) {
      controller?.off('stateChange');
      let stopped = true;
      if (startCalled && this.pipController === controller) {
        try { await controller?.stopPiP(); } catch (_) { stopped = false; }
      }
      if (stopped) {
        this.pipController = null; this.pipSession = null; this.pipPhase = 'idle';
        this.pictureInPictureAspect = -1;
        GycLiveClient.releasePictureInPicture(this);
      }
      // stop 失败仍保留 controller，后续会话清理须再结算，不能放行下一房间。
      this.notifyVisibility();
      throw error as Error;
    }
  }
  private async moveToBackground(session: NativeLiveSession, controller: PiPWindow.PiPController,
    context: common.UIAbilityContext): Promise<void> {
    try {
      if (!this.pipCurrent(session) || this.pipController !== controller || this.pipPhase !== 'started') return;
      if (!this.isForeground()) return;
      if (typeof context.moveAbilityToBackground !== 'function') throw new Error('Window context cannot move to background');
      await context.moveAbilityToBackground();
    } catch (_) {
      // 迟到的拒绝不能收回已经退到后台的窗口；仍在前台时才恢复原 Surface。
      if (!this.pipCurrent(session) || this.pipController !== controller || this.pipPhase !== 'started') return;
      if (!this.isForeground()) return;
      this.pipPhase = 'restoring';
      try { await controller.stopPiP(); }
      catch (_) {
        if (this.pipCurrent(session) && this.pipController === controller) {
          this.roomClosed = true;
          this.emit({ type: 'playbackError', generation: session.generation, code: -1, message: 'Picture in picture restore failed' });
          GycLiveClient.leave(session).catch(() => {});
          this.notifyVisibility();
        }
      }
    }
  }
  async restorePictureInPicture(): Promise<void> {
    if (!this.isForeground() || !this.pipController || !this.pipSession) throw new Error('Picture in picture is not ready to restore');
    const controller = this.pipController;
    this.pipPhase = 'restoring';
    try { await controller.stopPiP(); }
    catch (error) { if (this.pipController === controller) this.pipPhase = 'started'; throw error as Error; }
  }
  private async stopPictureInPicture(session: NativeLiveSession): Promise<void> {
    if (this.pipSession !== session) return;
    try { await this.pipStarting; } catch (_) {}
    const controller = this.pipController;
    try {
      controller?.off('stateChange');
      if (controller) await controller.stopPiP();
    } finally {
      this.reportPictureInPicture(false);
      this.pipController = null; this.pipSession = null; this.pipPhase = 'idle';
      this.pictureInPictureAspect = -1;
      GycLiveClient.releasePictureInPicture(this);
    }
  }
  private emit(event: LiveNativeEvent): void { if (!this.destroyed) this.nativeCallback?.(event); }
  playerViewParameters(session: NativeLiveSession): AtomicPlaybackViewParameters {
    // shortcut: 5.2.2 的内部 ArkUI 节点接收同一 Surface controller；公开 PiP 接口可用后移除此参数适配。
    return { view: session.view, mixSurfaceController: this.surfaceController };
  }
  private playerSurfaceReady(): boolean {
    try { return this.surfaceController.getXComponentSurfaceId().length > 0; }
    catch (_) { return false; }
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    if (this.destroyed) { callback?.({ success: false, message: 'View released' }); return null; }
    let value: Command;
    try { value = JSON.parse(typeof params === 'string' ? params : '{}') as Command; }
    catch (_) { callback?.({ success: false, message: 'Invalid command' }); return null; }
    if (method === 'enterPictureInPicture') {
      if (value === null || typeof value.wideContent !== 'boolean') {
        callback?.({ success: false, accepted: false, message: 'Invalid picture in picture layout' }); return null;
      }
      this.enterPictureInPicture(value.wideContent).then(() => callback?.({ success: true, accepted: true }))
        .catch((error: Error) => callback?.({ success: false, accepted: false, message: error.message }));
      return null;
    }
    // 宿主的预布局提示不是系统 PiP 状态，不能更新公共快照。
    if (method === 'updatePictureInPicture') { callback?.({ success: true }); return null; }
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
      if (this.roomClosed || !Number.isSafeInteger(generation) || generation! < this.publishedGeneration || typeof id !== 'string' || !id.trim() ||
        !this.attached || !this.isForeground() || !GycLiveClient.isReady()) {
        callback?.({ success: false, message: 'Not ready' }); return null;
      }
      const old = this.session;
      const session: NativeLiveSession = new NativeLiveSession(id, generation!, method === 'sdkPreview', event => this.emit(event),
        () => this.stopPictureInPicture(session));
      this.session = session;
      if (old) GycLiveClient.leave(old).catch(() => {});
      GycLiveClient.enter(session).then(() => {
        if (this.session !== session || this.destroyed || session.closed) throw new Error('Session released');
        const info = session.info;
        const result: Result = { success: true, liveName: info?.liveName || '', notice: info?.notice || '',
          ownerId: info?.liveOwner.userID || '', ownerName: info?.liveOwner.userName || '', ownerAvatarUrl: info?.liveOwner.avatarURL || '',
          userId: GycLiveClient.currentUserId() };
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
      else if (method === 'sdkCheckFollow') {
        session.checkFollowed(value.userId || '').then(followed => callback?.({ success: true, followed }))
          .catch((error: Error) => callback?.({ success: false, message: error.message }));
      } else if (method === 'sdkFollow') {
        session.updateFollow(value.userId || '', value.followed === true).then(() => callback?.({ success: true }))
          .catch((error: Error) => callback?.({ success: false, message: error.message }));
      } else if (method === 'sdkFans') {
        session.fetchFans(value.userId || '').then(fansCount => callback?.({ success: true, fansCount }))
          .catch((error: Error) => callback?.({ success: false, message: error.message }));
      }
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
