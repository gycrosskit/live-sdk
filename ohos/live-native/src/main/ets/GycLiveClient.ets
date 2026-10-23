import { AtomicPlayer, AtomicLiveController, AtomicLiveView, AtomicLiveViewType, BarrageStore,
  Barrage, BarrageType, LikeStore, LikeListener, LiveAudienceStore, LiveAudienceListener,
  LiveListStore, LiveListListener, LiveInfo, LiveUserInfo, LoginStore, LoginStatus,
  PlayerListener, PlayerPlaybackStatus, UserProfile, V2TIMManager } from '@tencentcloud/atomicxcore';
import { common } from '@kit.AbilityKit';
import { getTRTCShareInstance, TRTCCloud, TRTCCloudCallback, TRTCVideoStreamType } from '@tencentcloud/liteavsdk_professional';

export interface LiveNativeEvent {
  type: string;
  generation?: number;
  state?: string;
  count?: number;
  disabled?: boolean;
  enabled?: boolean;
  code?: number;
  message?: string;
  messages?: LiveMessage[];
  audience?: LiveUser[];
  ready?: boolean;
  user?: LiveUser;
  joined?: boolean;
  timestampSeconds?: number;
}
export interface LiveUser { id: string; name: string; avatarUrl: string; }
export interface LiveMessage {
  sequence: number; timestampSeconds: number; senderId: string; senderName: string;
  senderAvatarUrl: string; content: string; businessId: string; data: string;
}
class LiveAccountObserver {}
export interface LivePictureInPictureOwner { restorePictureInPicture(): Promise<void>; }
interface LivePictureInPictureObserver { changed: (enabled: boolean) => void; closed: () => void; }

/** 仅原生 SDK 操作串行；commonMain 仍负责观看排队和超时。未结算的 SDK Promise 不允许被下一房间越过。 */
export class GycLiveClient {
  private static queue: Promise<void> = Promise.resolve();
  private static owner: NativeLiveSession | null = null;
  private static preparedUser: string = '';
  private static preparedApp: number = 0;
  private static ownsLogin: boolean = false;
  private static listeners: Set<() => void> = new Set();
  private static accountRevision: number = 0;
  private static blocked: boolean = false;
  private static foreground: boolean = false;
  private static abilityForeground: Map<string, boolean> = new Map();
  private static ownedUser: string = '';
  private static ownedApp: number = 0;
  private static accountObserver: LiveAccountObserver = new LiveAccountObserver();
  private static stopAccountEvents: (() => void) | null = null;
  private static context: common.Context | null = null;
  private static pipOwner: LivePictureInPictureOwner | null = null;
  private static pipEnabled: boolean = false;
  private static pipObservers: Set<LivePictureInPictureObserver> = new Set();

  /** 无 Context 保留单 Ability 合同；metadata 作用域仅区分 singleton Ability，不区分同类多实例。 */
  static setForeground(value: boolean, context?: common.Context): void {
    const key = GycLiveClient.abilityKey(context);
    if (key) GycLiveClient.abilityForeground.set(key, value);
    else GycLiveClient.foreground = value;
    GycLiveClient.changed();
  }
  static isForeground(context?: common.Context): boolean {
    const key = GycLiveClient.abilityKey(context);
    return key ? GycLiveClient.abilityForeground.get(key) ?? GycLiveClient.foreground : GycLiveClient.foreground;
  }
  private static abilityKey(context?: common.Context): string {
    const info = (context as common.UIAbilityContext | undefined)?.abilityInfo;
    return info?.bundleName && info.moduleName && info.name ? `${info.bundleName}/${info.moduleName}/${info.name}` : '';
  }
  static mediaCloud(): TRTCCloud {
    if (!GycLiveClient.isReady() || !GycLiveClient.context) throw new Error('Live identity is not ready');
    return getTRTCShareInstance(GycLiveClient.context);
  }

  static observePictureInPicture(onChanged: (enabled: boolean) => void, onClosed: () => void): () => void {
    const observer: LivePictureInPictureObserver = { changed: onChanged, closed: onClosed };
    GycLiveClient.pipObservers.add(observer);
    onChanged(GycLiveClient.pipEnabled);
    return () => { GycLiveClient.pipObservers.delete(observer); };
  }
  static claimPictureInPicture(owner: LivePictureInPictureOwner): boolean {
    if (GycLiveClient.pipOwner && GycLiveClient.pipOwner !== owner) return false;
    GycLiveClient.pipOwner = owner;
    return true;
  }
  static pictureInPictureChanged(owner: LivePictureInPictureOwner, enabled: boolean, closed: boolean = false): void {
    if (GycLiveClient.pipOwner !== owner) return;
    GycLiveClient.pipEnabled = enabled;
    GycLiveClient.pipObservers.forEach(observer => {
      try { observer.changed(enabled); if (closed) observer.closed(); } catch (_) {}
    });
  }
  static releasePictureInPicture(owner: LivePictureInPictureOwner): void {
    if (GycLiveClient.pipOwner !== owner) return;
    if (GycLiveClient.pipEnabled) GycLiveClient.pictureInPictureChanged(owner, false);
    GycLiveClient.pipOwner = null;
  }
  static async restorePictureInPicture(): Promise<void> {
    if (!GycLiveClient.pipOwner) throw new Error('Picture in picture is not ready to restore');
    await GycLiveClient.pipOwner.restorePictureInPicture();
  }

  static serial<T>(operation: () => Promise<T>): Promise<T> {
    const result = GycLiveClient.queue.then(operation);
    GycLiveClient.queue = result.then(() => {}, () => {});
    return result;
  }
  static currentUserId(): string { return V2TIMManager.getInstance().getLoginUser(); }
  static isReady(): boolean {
    return !GycLiveClient.blocked && GycLiveClient.preparedUser.length > 0 &&
      GycLiveClient.currentUserId() === GycLiveClient.preparedUser &&
      V2TIMManager.getInstance().getLoginStatus() === 1 &&
      LoginStore.shared().state.loginStatus === LoginStatus.LOGINED &&
      LoginStore.shared().sdkAppID === GycLiveClient.preparedApp;
  }
  static observeReady(listener: () => void): () => void {
    GycLiveClient.listeners.add(listener);
    return () => { GycLiveClient.listeners.delete(listener); };
  }
  private static changed(): void { GycLiveClient.listeners.forEach(listener => { try { listener(); } catch (_) {} }); }

  /** 宿主在同意隐私后传入服务端 UserSig；拒绝接管外部账号，不持久化凭据。 */
  static async login(sdkAppId: number, userId: string, userSig: string, context: common.Context,
    nickname: string = '', avatarUrl: string = ''): Promise<void> {
    if (!Number.isInteger(sdkAppId) || sdkAppId <= 0 || !userId.trim() || !userSig.trim()) throw new Error('Invalid live identity');
    const revision = ++GycLiveClient.accountRevision;
    GycLiveClient.preparedUser = '';
    GycLiveClient.changed();
    await GycLiveClient.serial(async () => {
      if (revision !== GycLiveClient.accountRevision) throw new Error('Login superseded');
      if (GycLiveClient.blocked) {
        if (!GycLiveClient.currentUserId() && LoginStore.shared().state.loginStatus !== LoginStatus.LOGINED) GycLiveClient.blocked = false;
        else throw new Error('Previous leave failed; logout the owning SDK before login');
      }
      await GycLiveClient.owner?.close();
      const actual = GycLiveClient.currentUserId();
      const app = LoginStore.shared().sdkAppID;
      if (actual && (actual !== userId || app !== sdkAppId)) throw new Error('Foreign IM identity or unknown SDKAppId; logout its owner first');
      const ownsActual = GycLiveClient.ownsLogin && actual === GycLiveClient.ownedUser && app === GycLiveClient.ownedApp;
      const borrowed = actual.length > 0 && !ownsActual;
      if (!GycLiveClient.stopAccountEvents) {
        GycLiveClient.stopAccountEvents = LoginStore.shared().loginEventEmitter.subscribe(GycLiveClient.accountObserver, () => {
          ++GycLiveClient.accountRevision;
          GycLiveClient.preparedUser = '';
          GycLiveClient.changed();
          const session = GycLiveClient.owner;
          if (session) GycLiveClient.leave(session).catch(() => {});
        });
      }
      await LoginStore.shared().login(sdkAppId, userId, userSig, context);
      if (GycLiveClient.currentUserId() !== userId || LoginStore.shared().sdkAppID !== sdkAppId ||
        V2TIMManager.getInstance().getLoginStatus() !== 1 || LoginStore.shared().state.loginStatus !== LoginStatus.LOGINED) {
        throw new Error('SDK identity does not match');
      }
      GycLiveClient.ownsLogin = !borrowed;
      if (!borrowed) { GycLiveClient.ownedUser = userId; GycLiveClient.ownedApp = sdkAppId; }
      // 即使登录回执已失效，也记录本组件实际取得的身份，供后续 logout 安全清理。
      GycLiveClient.context = context;
      GycLiveClient.preparedUser = userId;
      GycLiveClient.preparedApp = sdkAppId;
      if (revision !== GycLiveClient.accountRevision) { GycLiveClient.preparedUser = ''; throw new Error('Login superseded'); }
      GycLiveClient.changed();
      const profile = new UserProfile(userId, nickname || userId, avatarUrl);
      try { LoginStore.shared().setSelfInfo(profile).catch(() => {}); } catch (_) {}
    });
  }

  static async logout(): Promise<void> {
    ++GycLiveClient.accountRevision;
    GycLiveClient.preparedUser = '';
    GycLiveClient.changed();
    await GycLiveClient.serial(async () => {
      await GycLiveClient.owner?.close();
      if (GycLiveClient.ownsLogin && GycLiveClient.currentUserId() === GycLiveClient.ownedUser && LoginStore.shared().sdkAppID === GycLiveClient.ownedApp) {
        await LoginStore.shared().logout();
        GycLiveClient.blocked = false;
      }
      GycLiveClient.ownsLogin = false;
      GycLiveClient.ownedUser = ''; GycLiveClient.ownedApp = 0;
      GycLiveClient.preparedApp = 0;
      GycLiveClient.context = null;
      GycLiveClient.stopAccountEvents?.(); GycLiveClient.stopAccountEvents = null;
    });
  }

  static async enter(session: NativeLiveSession): Promise<void> {
    await GycLiveClient.serial(async () => {
      if (!GycLiveClient.isReady()) throw new Error('Live identity is not ready');
      if (GycLiveClient.owner !== session) await GycLiveClient.owner?.close();
      if (session.closed) throw new Error('Session released');
      GycLiveClient.owner = session;
      try { await session.open(); }
      catch (error) { await session.close(); throw error as Error; }
    });
  }
  static leave(session: NativeLiveSession): Promise<void> {
    session.invalidate();
    return GycLiveClient.serial(async () => { await session.close(); });
  }
  static didClose(session: NativeLiveSession, succeeded: boolean): void {
    if (GycLiveClient.owner !== session) return;
    GycLiveClient.owner = null;
    if (!succeeded) { GycLiveClient.blocked = true; GycLiveClient.changed(); }
  }
}

class LiveDataListener implements DataChangeListener {
  private changed: () => void;
  constructor(changed: () => void) { this.changed = changed; }
  onDataReloaded(): void { this.changed(); }
  onDataAdded(_index: number): void { this.changed(); }
  onDataAdd(_index: number): void { this.changed(); }
  onDataMoved(_from: number, _to: number): void { this.changed(); }
  onDataMove(_from: number, _to: number): void { this.changed(); }
  onDataDeleted(_index: number): void { this.changed(); }
  onDataDelete(_index: number): void { this.changed(); }
  onDataChanged(_index: number): void { this.changed(); }
  onDataChange(_index: number): void { this.changed(); }
  onDatasetChange(_operations: DataOperation[]): void { this.changed(); }
}

/** 一实例一房间一代次；不维护业务快照、消息去重或点赞节流。 */
export class NativeLiveSession {
  readonly liveId: string;
  readonly generation: number;
  readonly preview: boolean;
  private emit: (event: LiveNativeEvent) => void;
  readonly player: AtomicPlayer | null;
  readonly view: AtomicLiveView | null;
  closed: boolean = false;
  info: LiveInfo | null = null;
  private joined: boolean = false;
  private joinAttempted: boolean = false;
  private closing: Promise<void> | null = null;
  private stopPresentation: () => Promise<void>;
  private timer: number = -1;
  private observing: boolean = false;
  private identity: string = GycLiveClient.currentUserId();
  private lastState: string = '';
  private userDisabled: boolean = false;
  private lastMessageDisabled: boolean = false;
  private barrage: BarrageStore | null = null;
  private audienceStore: LiveAudienceStore | null = null;
  private likeStore: LikeStore | null = null;
  private messageChanges: LiveDataListener = new LiveDataListener(() => this.messages());
  private audienceChanges: LiveDataListener = new LiveDataListener(() => this.audience());
  private liveListener: LiveListListener = new LiveListListener();
  private likeListener: LikeListener = new LikeListener();
  private audienceListener: LiveAudienceListener = new LiveAudienceListener();
  private playerListener: PlayerListener = new PlayerListener();
  private cloud: TRTCCloud | null = null;
  private trtcCallback: TRTCCloudCallback = {};
  private surfaceViewId: string = '';
  private availableVideo: Set<string> = new Set();
  private boundVideoUser: string = '';
  private boundVideoView: string = '';

  constructor(liveId: string, generation: number, preview: boolean, emit: (event: LiveNativeEvent) => void,
    stopPresentation: () => Promise<void> = async () => {}) {
    this.liveId = liveId;
    this.generation = generation;
    this.preview = preview;
    this.emit = emit;
    this.stopPresentation = stopPresentation;
    this.player = preview ? new AtomicPlayer() : null;
    this.view = preview ? new AtomicLiveView(AtomicLiveController.create(AtomicLiveViewType.playView)) : null;
    if (this.player && this.view) { this.player.setView(this.view); this.player.setVolume(0); }
    this.playerListener.playerError = (code, message) => this.event({ type: 'previewState', state: 'FAILED', code: code, message: message });
    this.playerListener.liveEnded = () => this.event({ type: 'previewState', state: 'FAILED' });
    this.playerListener.kickedOut = () => this.event({ type: 'previewState', state: 'FAILED' });
    this.trtcCallback.onUserVideoAvailable = (userId, available) => {
      if (!this.valid()) return;
      if (available) this.availableVideo.add(userId);
      else this.availableVideo.delete(userId);
      this.bindVideo();
    };
    this.trtcCallback.onError = (code, message) => this.event({ type: 'playbackError', code: code, message: message });
    this.liveListener.onLiveEnded = (id) => { if (id === liveId) this.event({ type: 'ended' }); };
    this.liveListener.onKickedOutOfLive = (id) => { if (id === liveId) this.event({ type: 'kickedOut' }); };
    this.likeListener.onReceiveLikesMessage = (id, count) => { if (id === liveId) this.event({ type: 'likes', count: count }); };
    this.audienceListener.onOwnerJoined = (user) => this.memberChanged(user, true);
    this.audienceListener.onOwnerLeft = (user) => this.memberChanged(user, false);
    this.audienceListener.onAdminJoined = (user) => this.memberChanged(user, true);
    this.audienceListener.onAdminLeft = (user) => this.memberChanged(user, false);
    this.audienceListener.onAudienceJoined = (user) => this.memberChanged(user, true);
    this.audienceListener.onAudienceLeft = (user) => this.memberChanged(user, false);
    this.audienceListener.onAudienceMessageDisabled = (user, disabled) => {
      if (user.userID === this.identity) {
        this.userDisabled = disabled;
        this.stateChanged();
      }
    };
  }

  async open(): Promise<void> {
    this.player?.addListener(this.playerListener);
    if (!this.preview) {
      // joinLive 已建立主 TRTC；提前监听真实发布者，不能另拉仅适用于 feedback 混流的 AtomicPlayer。
      this.cloud = GycLiveClient.mediaCloud();
      this.cloud.addCallback(this.trtcCallback);
      LiveListStore.shared().addLiveListListener(this.liveListener);
      this.joinAttempted = true;
      this.info = await LiveListStore.shared().joinLive(this.liveId);
      this.joined = true;
    }
    if (this.closed) { await this.close(); throw new Error('Session released'); }
    // preview 保持只拉流、不加入完整直播间的既有合同。
    this.player?.startPlay(`livekit://liveid=${this.liveId}`);
    this.bindVideo();
    // 完整观看等 common 确认 joined 后由 sdkObserve 启用，避免初始状态被提前丢弃。
    this.timer = setInterval(() => this.stateChanged(), 250);
  }

  setSurfaceView(viewId: string): void {
    if (this.closed || this.preview || this.surfaceViewId === viewId) return;
    this.surfaceViewId = viewId;
    this.bindVideo();
  }
  private bindVideo(): void {
    const owner = this.info?.liveOwner.userID || '';
    const cloud = this.cloud;
    if (!this.valid() || !this.joined || !cloud) return;
    try {
      if (!owner || !this.surfaceViewId || !this.availableVideo.has(owner)) { this.stopVideo(); return; }
      if (this.boundVideoUser === owner && this.boundVideoView === this.surfaceViewId) return;
      if (this.boundVideoUser === owner) cloud.updateRemoteView(owner, TRTCVideoStreamType.TRTCVideoStreamTypeBig, this.surfaceViewId);
      else {
        this.stopVideo();
        cloud.startRemoteView(owner, TRTCVideoStreamType.TRTCVideoStreamTypeBig, this.surfaceViewId);
      }
      this.boundVideoUser = owner;
      this.boundVideoView = this.surfaceViewId;
    } catch (error) {
      this.event({ type: 'playbackError', code: -1, message: (error as Error).message || 'Video binding failed' });
    }
  }
  private stopVideo(): void {
    const user = this.boundVideoUser;
    this.boundVideoUser = ''; this.boundVideoView = '';
    if (user) this.cloud?.stopRemoteView(user, TRTCVideoStreamType.TRTCVideoStreamTypeBig);
  }

  observe(): void {
    if (this.closed || !this.joined) return;
    if (!this.observing) {
      this.observing = true;
      this.barrage = BarrageStore.create(this.liveId);
      this.audienceStore = LiveAudienceStore.create(this.liveId);
      this.likeStore = LikeStore.create(this.liveId);
      this.barrage.barrageState.messageList.registerDataChangeListener(this.messageChanges);
      this.audienceStore.liveAudienceState.audienceList.registerDataChangeListener(this.audienceChanges);
      this.audienceStore.addLiveAudienceListener(this.audienceListener);
      this.likeStore.addLikeListener(this.likeListener);
    }
    this.messages(); this.audience(); this.stateChanged();
    this.audienceStore?.fetchAudienceList().then(() => this.audience()).catch(() => {});
  }
  async sendBarrage(message: string): Promise<void> {
    if (!this.valid() || !this.barrage || !message.trim()) throw new Error('Not ready');
    await this.barrage.sendTextMessage(message, new Map<string, string>());
    if (!this.valid()) throw new Error('Session released');
  }
  async sendLike(count: number): Promise<void> {
    if (!this.valid() || !this.likeStore || !Number.isInteger(count) || count <= 0) throw new Error('Not ready');
    await this.likeStore.sendLike(count);
    if (!this.valid()) throw new Error('Session released');
  }
  private valid(): boolean { return !this.closed && GycLiveClient.isReady() && this.identity === GycLiveClient.currentUserId(); }
  private memberChanged(user: LiveUserInfo, joined: boolean): void {
    this.event({ type: 'memberChanged', user: { id: user.userID, name: user.userName, avatarUrl: user.avatarURL },
      joined: joined, timestampSeconds: Date.now() / 1000 });
  }
  private event(event: LiveNativeEvent): void {
    if (this.valid()) { event.generation = this.generation; this.emit(event); }
  }
  private messages(): void {
    if (!this.valid() || !this.barrage) return;
    const messages = this.barrage.barrageState.messageList.getAllData().slice(-200).map((item: Barrage): LiveMessage => {
      return { sequence: item.sequence, timestampSeconds: item.timestampInSecond,
        senderId: item.sender.userID, senderName: item.sender.userName, senderAvatarUrl: item.sender.avatarURL,
        content: item.messageType === BarrageType.TEXT ? item.textContent : '', businessId: item.businessID, data: item.data };
    });
    this.event({ type: 'messages', messages: messages });
  }
  private audience(): void {
    if (!this.valid() || !this.audienceStore) return;
    this.event({ type: 'audience', count: this.audienceStore.liveAudienceState.audienceCount,
      audience: this.audienceStore.liveAudienceState.audienceList.getAllData().map((user: LiveUserInfo): LiveUser => {
        return { id: user.userID, name: user.userName, avatarUrl: user.avatarURL };
      }) });
  }
  private stateChanged(): void {
    if (this.closed) return;
    if (!this.valid()) { this.emit({ type: 'visibility', ready: false }); return; }
    if (this.preview) {
      const state = this.player?.getState().playbackStatus;
      const name = state === PlayerPlaybackStatus.PLAYING ? 'PLAYING' : 'LOADING';
      if (name !== this.lastState) { this.lastState = name; this.event({ type: 'previewState', state: name }); }
    } else if (this.audienceStore && this.barrage) {
      const disabled = this.userDisabled || this.barrage.barrageState.isMessageDisable ||
        this.audienceStore.liveAudienceState.messageBannedUserList.getAllData().some((user: LiveUserInfo) => user.userID === this.identity);
      const key = `${this.audienceStore.liveAudienceState.audienceCount}:${disabled}`;
      if (key !== this.lastState) {
        this.lastState = key; this.audience();
      }
      // 首次未禁言及人数变化只是快照，不能冒充一次解除禁言通知。
      if (disabled !== this.lastMessageDisabled) {
        this.lastMessageDisabled = disabled;
        this.event({ type: 'messageDisabled', disabled: disabled });
      }
    }
  }
  invalidate(): void { this.closed = true; }
  close(): Promise<void> {
    if (this.closing) return this.closing;
    this.closed = true;
    this.closing = this.cleanup();
    return this.closing;
  }
  private async cleanup(): Promise<void> {
    if (this.timer >= 0) { clearInterval(this.timer); this.timer = -1; }
    let cleanupFailed = false;
    // PiP 必须在共享房间退出前收尾，包括账号 logout 触发的直接会话释放。
    try { await this.stopPresentation(); } catch (_) { cleanupFailed = true; }
    const cleanup: Array<() => void> = [
      () => this.cloud?.removeCallback(this.trtcCallback), () => this.stopVideo(),
      () => this.player?.removeListener(this.playerListener), () => this.player?.stopPlay(),
      () => LiveListStore.shared().removeLiveListListener(this.liveListener),
      () => this.barrage?.barrageState.messageList.unregisterDataChangeListener(this.messageChanges),
      () => this.audienceStore?.liveAudienceState.audienceList.unregisterDataChangeListener(this.audienceChanges),
      () => this.audienceStore?.removeLiveAudienceListener(this.audienceListener),
      () => this.likeStore?.removeLikeListener(this.likeListener)
    ];
    cleanup.forEach(action => { try { action(); } catch (_) { cleanupFailed = true; } });
    this.cloud = null; this.surfaceViewId = ''; this.availableVideo.clear();
    try {
      if (this.joinAttempted) await LiveListStore.shared().leaveLive();
      GycLiveClient.didClose(this, !cleanupFailed);
      if (cleanupFailed) throw new Error('SDK cleanup failed');
    } catch (error) { GycLiveClient.didClose(this, false); throw error as Error; }
    finally { this.joined = false; }
  }
}
