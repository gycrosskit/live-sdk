import { AtomicPlayer, AtomicLiveController, AtomicLiveView, AtomicLiveViewType, BarrageStore,
  Barrage, BarrageType, LikeStore, LikeListener, LiveAudienceStore, LiveAudienceListener,
  LiveListStore, LiveListListener, LiveInfo, LiveUserInfo, LoginStore, LoginStatus,
  PlayerListener, PlayerPlaybackStatus, UserProfile, V2TIMManager } from '@tencentcloud/atomicxcore';
import { common } from '@kit.AbilityKit';

export interface LiveNativeEvent {
  type: string;
  generation?: number;
  state?: string;
  count?: number;
  disabled?: boolean;
  code?: number;
  message?: string;
  messages?: LiveMessage[];
  audience?: LiveUser[];
  ready?: boolean;
}
export interface LiveUser { id: string; name: string; avatarUrl: string; }
export interface LiveMessage {
  sequence: number; timestampSeconds: number; senderId: string; senderName: string;
  senderAvatarUrl: string; content: string; businessId: string; data: string;
}
class LiveAccountObserver {}

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
  private static ownedUser: string = '';
  private static ownedApp: number = 0;
  private static accountObserver: LiveAccountObserver = new LiveAccountObserver();
  private static stopAccountEvents: (() => void) | null = null;

  /** 宿主 Ability 在 onForeground/onBackground 同步，后台默认停止观看与预览。 */
  static setForeground(value: boolean): void { GycLiveClient.foreground = value; GycLiveClient.changed(); }
  static isForeground(): boolean { return GycLiveClient.foreground; }

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
  readonly player: AtomicPlayer = new AtomicPlayer();
  readonly view: AtomicLiveView = new AtomicLiveView(AtomicLiveController.create(AtomicLiveViewType.playView));
  closed: boolean = false;
  info: LiveInfo | null = null;
  private joined: boolean = false;
  private joinAttempted: boolean = false;
  private closing: Promise<void> | null = null;
  private timer: number = -1;
  private observing: boolean = false;
  private identity: string = GycLiveClient.currentUserId();
  private lastState: string = '';
  private userDisabled: boolean = false;
  private barrage: BarrageStore | null = null;
  private audienceStore: LiveAudienceStore | null = null;
  private likeStore: LikeStore | null = null;
  private messageChanges: LiveDataListener = new LiveDataListener(() => this.messages());
  private audienceChanges: LiveDataListener = new LiveDataListener(() => this.audience());
  private liveListener: LiveListListener = new LiveListListener();
  private likeListener: LikeListener = new LikeListener();
  private audienceListener: LiveAudienceListener = new LiveAudienceListener();
  private playerListener: PlayerListener = new PlayerListener();

  constructor(liveId: string, generation: number, preview: boolean, emit: (event: LiveNativeEvent) => void) {
    this.liveId = liveId;
    this.generation = generation;
    this.preview = preview;
    this.emit = emit;
    this.player.setView(this.view);
    this.player.setVolume(preview ? 0 : 100);
    this.playerListener.playerError = (code, message) => {
      if (preview) this.event({ type: 'previewState', state: 'FAILED', code: code, message: message });
      else this.event({ type: 'playbackError', code: code, message: message });
    };
    this.playerListener.liveEnded = () => this.event({ type: preview ? 'previewState' : 'ended', state: 'FAILED' });
    this.playerListener.kickedOut = () => this.event({ type: preview ? 'previewState' : 'kickedOut', state: 'FAILED' });
    this.liveListener.onLiveEnded = (id) => { if (id === liveId) this.event({ type: 'ended' }); };
    this.liveListener.onKickedOutOfLive = (id) => { if (id === liveId) this.event({ type: 'kickedOut' }); };
    this.likeListener.onReceiveLikesMessage = (id, count) => { if (id === liveId) this.event({ type: 'likes', count: count }); };
    this.audienceListener.onAudienceMessageDisabled = (user, disabled) => {
      if (user.userID === this.identity) {
        this.userDisabled = disabled;
        this.event({ type: 'messageDisabled', disabled: disabled || this.barrage?.barrageState.isMessageDisable === true });
      }
    };
  }

  async open(): Promise<void> {
    this.player.addListener(this.playerListener);
    if (!this.preview) {
      LiveListStore.shared().addLiveListListener(this.liveListener);
      this.joinAttempted = true;
      this.info = await LiveListStore.shared().joinLive(this.liveId);
      this.joined = true;
    }
    if (this.closed) { await this.close(); throw new Error('Session released'); }
    // 与腾讯 live-v2 官方播放器一致；preview 只拉流，不加入完整直播间。
    this.player.startPlay(`livekit://liveid=${this.liveId}`);
    if (!this.preview) this.observe();
    this.timer = setInterval(() => this.stateChanged(), 250);
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
    const state = this.player.getState().playbackStatus;
    if (this.preview) {
      const name = state === PlayerPlaybackStatus.PLAYING ? 'PLAYING' : 'LOADING';
      if (name !== this.lastState) { this.lastState = name; this.event({ type: 'previewState', state: name }); }
    } else if (this.audienceStore && this.barrage) {
      const disabled = this.userDisabled || this.barrage.barrageState.isMessageDisable ||
        this.audienceStore.liveAudienceState.messageBannedUserList.getAllData().some((user: LiveUserInfo) => user.userID === this.identity);
      const key = `${this.audienceStore.liveAudienceState.audienceCount}:${disabled}`;
      if (key !== this.lastState) {
        this.lastState = key; this.audience();
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
    const cleanup: Array<() => void> = [
      () => this.player.removeListener(this.playerListener), () => this.player.stopPlay(),
      () => LiveListStore.shared().removeLiveListListener(this.liveListener),
      () => this.barrage?.barrageState.messageList.unregisterDataChangeListener(this.messageChanges),
      () => this.audienceStore?.liveAudienceState.audienceList.unregisterDataChangeListener(this.audienceChanges),
      () => this.audienceStore?.removeLiveAudienceListener(this.audienceListener),
      () => this.likeStore?.removeLikeListener(this.likeListener)
    ];
    cleanup.forEach(action => { try { action(); } catch (_) { cleanupFailed = true; } });
    try {
      if (this.joinAttempted) await LiveListStore.shared().leaveLive();
      GycLiveClient.didClose(this, !cleanupFailed);
      if (cleanupFailed) throw new Error('SDK cleanup failed');
    } catch (error) { GycLiveClient.didClose(this, false); throw error as Error; }
    finally { this.joined = false; }
  }
}
