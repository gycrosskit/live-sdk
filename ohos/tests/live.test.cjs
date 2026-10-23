const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require(process.env.TYPESCRIPT_PATH || '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript');
const root = path.resolve(__dirname, '../live-native/src/main/ets');
const deferred = () => { let resolve, reject; const promise = new Promise((r, j) => { resolve = r; reject = j; }); return { promise, resolve, reject }; };
const tick = async () => { for (let i = 0; i < 24; i++) await Promise.resolve(); };

function fixture() {
  const calls = [];
  const timers = new Map();
  let nextTimer = 0;
  const identity = { user: '', status: 3 };
  let accountEvent = null;
  const login = { sdkAppID: 0, state: { loginStatus: 3 }, loginEventEmitter: { subscribe(target, callback) { accountEvent = callback; return () => { accountEvent = null; }; } },
    async login(app, user) { calls.push(['login', user]); this.sdkAppID = app; identity.user = user; identity.status = 1; this.state.loginStatus = 1; },
    async logout() { calls.push(['logout']); identity.user = ''; identity.status = 3; this.state.loginStatus = 3; },
    async setSelfInfo() {},
  };
  const source = () => ({ listeners: new Set(), values: [], getAllData() { return this.values; },
    registerDataChangeListener(value) { this.listeners.add(value); }, unregisterDataChangeListener(value) { this.listeners.delete(value); } });
  const barrage = { barrageState: { messageList: source(), isMessageDisable: false }, async sendTextMessage(message) { calls.push(['barrage', message]); if (barrage.pending) await barrage.pending.promise; } };
  const audience = { listeners: new Set(), liveAudienceState: { audienceCount: 0, audienceList: source(), messageBannedUserList: source() }, async fetchAudienceList() {},
    addLiveAudienceListener(value) { this.listeners.add(value); }, removeLiveAudienceListener(value) { this.listeners.delete(value); } };
  const likes = { async sendLike(count) { calls.push(['like', count]); }, addLikeListener() {}, removeLikeListener() {} };
  const live = { listeners: new Set(), join: null, leave: null,
    async joinLive(id) { calls.push(['join', id]); if (this.join) await this.join.promise; return { liveName: id, notice: '', liveOwner: { userID: 'host', userName: '', avatarURL: '' } }; },
    async leaveLive() { calls.push(['leave']); if (this.leave) await this.leave.promise; },
    addLiveListListener(value) { this.listeners.add(value); }, removeLiveListListener(value) { this.listeners.delete(value); },
  };
  const cloud = { callbacks: new Set(),
    addCallback(value) { calls.push(['addCallback']); this.callbacks.add(value); },
    removeCallback(value) { calls.push(['removeCallback']); this.callbacks.delete(value); },
    startRemoteView(user, type, view) { calls.push(['startRemote', user, type, view]); },
    updateRemoteView(user, type, view) { calls.push(['updateRemote', user, type, view]); },
    stopRemoteView(user, type) { calls.push(['stopRemote', user, type]); },
    enterRoom() { calls.push(['enterRoom']); }, exitRoom() { calls.push(['exitRoom']); }, destroySharedInstance() { calls.push(['destroyShared']); },
    video(user, available) { for (const callback of this.callbacks) callback.onUserVideoAvailable?.(user, available); },
  };
  class Player {
    constructor() { calls.push(['newPlayer']); }
    state = { playbackStatus: 0 }; listener = null;
    setView() {} setVolume(value) { this.volume = value; }
    addListener(value) { this.listener = value; } removeListener() { this.listener = null; }
    startPlay(url) { calls.push(['play', url]); } stopPlay() { calls.push(['stop']); }
    getState() { return this.state; }
  }
  const ability = { moveError: null, moveWait: null };
  const windowContext = { windowContext: true, async moveAbilityToBackground() { calls.push(['moveBackground']); if (ability.moveWait) await ability.moveWait.promise; if (ability.moveError) throw ability.moveError; } };
  class BaseView { hostContext = windowContext; getUIContext() { return { getHostContext: () => this.hostContext }; } setProp() { return false; } onDestroy() {} }
  const sdk = { AtomicPlayer: Player, AtomicLiveController: { create() { return {}; } }, AtomicLiveView: class {}, AtomicLiveViewType: { playView: 0 },
    BarrageStore: { create: () => barrage }, BarrageType: { TEXT: 0 }, LikeStore: { create: () => likes }, LikeListener: class {},
    LiveAudienceStore: { create: () => audience }, LiveAudienceListener: class {}, LiveListStore: { shared: () => live }, LiveListListener: class {},
    LoginStore: { shared: () => login }, LoginStatus: { LOGINED: 1 }, PlayerListener: class {}, PlayerPlaybackStatus: { PLAYING: 2 }, UserProfile: class {},
    V2TIMManager: { getInstance: () => ({ getLoginUser: () => identity.user, getLoginStatus: () => identity.status }) } };
  const pip = { supported: true, controllers: [], createWait: null, startWait: null,
    PiPState: { ABOUT_TO_START: 1, STARTED: 2, ABOUT_TO_STOP: 3, STOPPED: 4, ABOUT_TO_RESTORE: 5, ERROR: 6 },
    PiPTemplateType: { VIDEO_LIVE: 3 },
    isPiPEnabled() { return this.supported; },
    async create(config) {
      calls.push(['createPiP', config]); if (this.createWait) await this.createWait.promise;
      const controller = { listener: null,
        on(type, callback) { this.listener = callback; }, off() { this.listener = null; },
        setAutoStartEnabled(enabled) { calls.push(['autoPiP', enabled]); },
        async startPiP() { calls.push(['startPiP']); this.emit(1); if (pip.startWait) await pip.startWait.promise; },
        async stopPiP() { calls.push(['stopPiP']); this.emit(3); this.emit(4); },
        emit(state) { this.listener?.(state, 'test'); },
      };
      this.controllers.push(controller); return controller;
    },
  };
  const cache = new Map();
  function load(name) {
    if (cache.has(name)) return cache.get(name);
    const result = ts.transpileModule(fs.readFileSync(path.join(root, name + '.ets'), 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2021, experimentalDecorators: true }, reportDiagnostics: true });
    assert.equal(result.diagnostics.filter(d => d.category === ts.DiagnosticCategory.Error).length, 0);
    const module = { exports: {} };
    vm.runInNewContext(result.outputText, { module, exports: module.exports, require(name) {
      if (name === '@tencentcloud/atomicxcore') return sdk;
      if (name === '@tencentcloud/liteavsdk_professional') return { getTRTCShareInstance(context) { calls.push(['sharedCloud', context]); return cloud; }, TRTCVideoStreamType: { TRTCVideoStreamTypeBig: 0 } };
      if (name === '@kit.AbilityKit') return {};
      if (name === '@kuikly-open/render') return { KuiklyRenderBaseView: BaseView };
      if (name === '@ohos.PiPWindow') return { default: pip };
      if (name === '@ohos.arkui.node') return { ComponentContent: class {} };
      if (name === './LiveComponent') return { createGycLiveView() {} };
      return load(name.replace('./', ''));
    }, XComponentController: class {}, Observed: value => value, setInterval(fn) { const id = ++nextTimer; timers.set(id, fn); return id; }, clearInterval(id) { timers.delete(id); },
      setTimeout(fn) { const id = ++nextTimer; timers.set(id, fn); return id; }, clearTimeout(id) { timers.delete(id); },
      Map, Set, Number, Promise, Error, encodeURIComponent }, { filename: name + '.ets' });
    cache.set(name, module.exports); return module.exports;
  }
  const { GycLiveClient: client, NativeLiveSession: Session } = load('GycLiveClient');
  return { client, Session, View: load('GycLiveView').GycLiveView, calls, timers, identity, login, live, barrage, audience, cloud, pip, ability, windowContext, accountExpired() { accountEvent?.(); } };
}

test('owned login does not wait for user profile; owned logout cleans the actual identity', async () => {
  const f = fixture(); f.login.setSelfInfo = () => new Promise(() => {});
  await f.client.login(123, 'viewer', 'sig', {});
  assert.equal(f.client.isReady(), true);
  await f.client.logout();
  assert.equal(f.client.isReady(), false); assert.equal(f.identity.user, '');
});

test('foreign user/app is rejected; borrowed identity survives logout', async () => {
  const f = fixture(); f.identity.user = 'external'; f.identity.status = 1; f.login.sdkAppID = 123;
  await assert.rejects(f.client.login(123, 'viewer', 'sig', {}), /Foreign/);
  await assert.rejects(f.client.login(456, 'external', 'sig', {}), /Foreign/);
  f.login.sdkAppID = 0;
  await assert.rejects(f.client.login(123, 'external', 'sig', {}), /Foreign/);
  f.login.sdkAppID = 123;
  await f.client.login(123, 'external', 'sig', {});
  await f.client.logout();
  assert.equal(f.identity.user, 'external'); assert.equal(f.calls.some(c => c[0] === 'logout'), false);
});

test('preview is muted and never joins the complete live room; stop clears the state timer', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {});
  const events = []; const session = new f.Session('room-A', 1, true, event => events.push(event));
  await f.client.enter(session);
  assert.equal(session.player.volume, 0);
  assert.equal(f.calls.some(c => c[0] === 'join'), false);
  assert.equal(f.calls.some(c => c[0] === 'play' && c[1] === 'livekit://liveid=room-A'), true);
  session.player.state.playbackStatus = 2; [...f.timers.values()][0]();
  assert.equal(events.at(-1).state, 'PLAYING');
  await f.client.leave(session); assert.equal(f.timers.size, 0);
});

test('release while joining waits for join and leave before allowing the next room', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {});
  f.live.join = deferred(); f.live.leave = deferred();
  const a = new f.Session('A', 1, false, () => {});
  const entering = f.client.enter(a); const failed = assert.rejects(entering, /released/);
  await tick(); const leaving = f.client.leave(a);
  const b = new f.Session('B', 2, false, () => {}); const next = f.client.enter(b);
  await tick(); assert.equal(f.calls.filter(c => c[0] === 'join').length, 1);
  f.live.join.resolve(); await tick();
  assert.equal(f.calls.some(c => c[0] === 'join' && c[1] === 'B'), false);
  f.live.leave.resolve(); await failed; await leaving; await next;
  assert.equal(f.calls.some(c => c[0] === 'join' && c[1] === 'B'), true);
  await f.client.leave(b);
});

test('SDK dataset callbacks publish updated barrage contents', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {});
  const events = []; const session = new f.Session('A', 1, false, event => events.push(event));
  await f.client.enter(session); session.observe();
  const source = f.barrage.barrageState.messageList;
  const listener = [...source.listeners][0];
  source.values = [{ sequence: 1, timestampInSecond: 1, sender: { userID: 'u', userName: 'viewer', avatarURL: '' },
    messageType: 0, textContent: 'first', businessID: '', data: '' }];
  listener.onDataAdded(0);
  assert.equal(events.filter(e => e.type === 'messages').at(-1).messages[0].content, 'first');
  source.values[0].textContent = 'updated'; listener.onDatasetChange([]);
  assert.equal(events.filter(e => e.type === 'messages').at(-1).messages[0].content, 'updated');
  await f.client.leave(session); assert.equal(source.listeners.size, 0);
});

test('released barrage cannot report success and old events stop reaching the observer', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {});
  const events = []; const session = new f.Session('A', 1, false, event => events.push(event));
  await f.client.enter(session); session.observe();
  f.barrage.pending = deferred(); const sending = session.sendBarrage('hello'); const failed = assert.rejects(sending, /released/);
  const listener = [...f.live.listeners][0]; const mediaCallback = [...f.cloud.callbacks][0];
  await f.client.leave(session); const count = events.length;
  listener.onLiveEnded('A'); mediaCallback.onError(-1, 'late'); f.barrage.pending.resolve(); await failed;
  assert.equal(events.length, count); assert.equal(f.barrage.barrageState.messageList.listeners.size, 0);
});

test('initial unmuted state and audience counts do not impersonate mute changes', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {});
  const events = []; const session = new f.Session('A', 1, false, event => events.push(event));
  await f.client.enter(session); session.observe();
  const tickState = [...f.timers.values()][0];
  const muteEvents = () => events.filter(event => event.type === 'messageDisabled').map(event => event.disabled);
  f.audience.liveAudienceState.audienceCount = 2; tickState();
  assert.deepEqual(muteEvents(), []);
  const listener = [...f.audience.listeners][0];
  listener.onAudienceMessageDisabled({ userID: 'other' }, true);
  assert.deepEqual(muteEvents(), []);
  listener.onAudienceMessageDisabled({ userID: 'viewer' }, true); tickState();
  f.audience.liveAudienceState.audienceCount = 3; tickState();
  assert.deepEqual(muteEvents(), [true]);
  listener.onAudienceMessageDisabled({ userID: 'viewer' }, false); tickState();
  assert.deepEqual(muteEvents(), [true, false]);
  f.barrage.barrageState.isMessageDisable = true; tickState(); tickState();
  f.barrage.barrageState.isMessageDisable = false; tickState();
  assert.deepEqual(muteEvents(), [true, false, true, false]);
  await f.client.leave(session);
});

test('owner admin and audience membership callbacks retain semantic data and stop after release', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {});
  const events = []; const session = new f.Session('A', 1, false, event => events.push(event));
  await f.client.enter(session); session.observe();
  const listener = [...f.audience.listeners][0];
  const user = { userID: 'member', userName: 'name', avatarURL: 'avatar' };
  for (const role of ['Owner', 'Admin', 'Audience']) {
    listener['on' + role + 'Joined'](user); listener['on' + role + 'Left'](user);
  }
  const members = events.filter(event => event.type === 'memberChanged');
  assert.deepEqual(members.map(event => event.joined), [true, false, true, false, true, false]);
  assert.ok(members.every(event => event.user.id === 'member' && event.user.name === 'name' && event.user.avatarUrl === 'avatar' && event.timestampSeconds > 0));
  assert.equal(events.some(event => event.type === 'messageDisabled'), false);
  await f.client.leave(session);
  listener.onAudienceJoined(user);
  assert.equal(events.filter(event => event.type === 'memberChanged').length, 6);
});

test('initial mute waits for common joined acknowledgement and repeated observation does not duplicate it', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {}); f.client.setForeground(true);
  f.barrage.barrageState.isMessageDisable = true;
  const events = []; const view = new f.View(); view.attach(); view.setProp('nativeLiveEvent', event => events.push(event));
  let joined; view.call('sdkJoin', JSON.stringify({ generation: 1, liveId: 'A' }), reply => joined = reply);
  await tick(); assert.equal(joined.success, true);
  assert.equal(events.some(event => event.type === 'messageDisabled'), false);
  assert.equal(f.audience.listeners.size, 0);
  view.call('sdkObserve', JSON.stringify({ generation: 1 }), null);
  view.call('sdkObserve', JSON.stringify({ generation: 1 }), null);
  assert.deepEqual(events.filter(event => event.type === 'messageDisabled').map(event => event.disabled), [true]);
  view.onDestroy(); await tick(); assert.equal(f.audience.listeners.size, 0);
});

test('failed leave blocks later rooms instead of pretending the SDK is idle', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {});
  const a = new f.Session('A', 1, false, () => {}); await f.client.enter(a);
  f.live.leave = deferred(); const leaving = f.client.leave(a); const failed = assert.rejects(leaving, /network/);
  await tick(); f.live.leave.reject(new Error('network')); await failed;
  assert.equal(f.client.isReady(), false);
  await assert.rejects(f.client.enter(new f.Session('B', 2, false, () => {})), /not ready/);
  await f.client.logout(); await f.client.login(123, 'viewer', 'sig', {});
  assert.equal(f.client.isReady(), true);
});

test('native view forwards configuration and commands, settles pending replies on destroy', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {}); f.client.setForeground(true);
  const events = []; const view = new f.View();
  view.setProp('nativeLiveEvent', event => events.push(event)); view.setProp('room', JSON.stringify({ liveId: 'A' })); view.attach();
  assert.equal(events.at(-1).visible, true);
  let result; view.call('sendBarrage', JSON.stringify({ message: 'hello' }), value => result = value);
  assert.equal(events.at(-1).method, 'sendBarrage'); assert.equal(result, undefined);
  view.onDestroy(); assert.equal(result.success, false);
  view.onDestroy();
});

test('foreground changes gate playback without losing the prepared account', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {});
  const events = []; const view = new f.View(); view.setProp('nativeLiveEvent', e => events.push(e)); view.attach();
  f.client.setForeground(true); assert.equal(events.at(-1).visible, true);
  f.client.setForeground(false); assert.equal(events.at(-1).visible, false); assert.equal(f.client.isReady(), true);
  view.onDestroy();
});

test('expired credentials close the account gate immediately', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {});
  f.accountExpired(); assert.equal(f.client.isReady(), false);
  await f.client.logout(); assert.equal(f.identity.user, '');
});

test('join failure still leaves; a throwing media cleanup does not skip SDK leave', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {});
  f.live.join = deferred(); const a = new f.Session('A', 1, false, () => {});
  const joining = f.client.enter(a); const failed = assert.rejects(joining, /bad room/);
  await tick(); f.live.join.reject(new Error('bad room')); await failed;
  assert.equal(f.calls.filter(c => c[0] === 'leave').length, 1);
  f.live.join = null;
  const b = new f.Session('B', 2, false, () => {}); await f.client.enter(b);
  b.setSurfaceView('video-B'); f.cloud.video('host', 1);
  f.cloud.stopRemoteView = () => { throw new Error('native stop'); };
  await assert.rejects(f.client.leave(b), /cleanup failed/);
  assert.equal(f.calls.filter(c => c[0] === 'leave').length, 2);
});

test('SDK view rejects a stale leave and suppresses old snapshot publication', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {}); f.client.setForeground(true);
  const view = new f.View(); view.attach(); const events = []; view.setProp('liveEvent', e => events.push(e));
  let result; view.call('sdkJoin', JSON.stringify({ generation: 2, liveId: 'B' }), r => result = r);
  await tick(); assert.equal(result.success, true);
  let stale; view.call('sdkLeave', JSON.stringify({ generation: 1 }), r => stale = r);
  assert.equal(stale.success, false); assert.equal(view.session.liveId, 'B');
  view.call('publish', JSON.stringify({ generation: 2, event: { type: 'snapshot', room: 'B' } }), null);
  view.call('publish', JSON.stringify({ generation: 1, event: { type: 'snapshot', room: 'A' } }), null);
  assert.deepEqual(events.map(e => e.room), ['B']);
  view.onDestroy(); await tick(); assert.equal(f.timers.size, 0);
});

test('public command timeout settles once and ignores the late completion', async () => {
  const f = fixture(); const view = new f.View(); const events = []; const results = [];
  view.setProp('nativeLiveEvent', e => events.push(e));
  view.call('sendBarrage', JSON.stringify({ message: 'hello' }), r => results.push(r));
  const id = events.at(-1).requestId; [...f.timers.values()][0]();
  view.call('complete', JSON.stringify({ requestId: id, success: true }), null);
  assert.equal(results.length, 1); assert.equal(results[0].success, false);
  view.onDestroy();
});


test('watch caches real availability before join acknowledgement and binds only the reported owner after Surface readiness', async () => {
  const f = fixture(); const context = {}; await f.client.login(123, 'viewer', 'sig', context);
  f.live.join = deferred();
  const session = new f.Session('A', 1, false, () => {}); const entering = f.client.enter(session);
  await tick();
  assert.equal(f.calls.find(c => c[0] === 'sharedCloud')[1], context);
  assert.ok(f.calls.findIndex(c => c[0] === 'addCallback') < f.calls.findIndex(c => c[0] === 'join'));
  f.cloud.video('unrelated', 1); f.cloud.video('host', 1);
  session.setSurfaceView('video-A');
  assert.equal(f.calls.some(c => c[0] === 'startRemote'), false);
  f.live.join.resolve(); await entering;
  assert.deepEqual(f.calls.filter(c => c[0] === 'startRemote'), [['startRemote', 'host', 0, 'video-A']]);
  f.cloud.video('host', 1); f.cloud.video('unrelated', 0);
  assert.equal(f.calls.filter(c => c[0] === 'startRemote').length, 1);
  assert.equal(session.player, null); assert.equal(session.view, null);
  assert.equal(f.calls.some(c => ['newPlayer', 'play', 'enterRoom', 'exitRoom', 'destroyShared'].includes(c[0])), false);
  await f.client.leave(session);
});

test('watch handles callback after join, Surface detach and replacement, and owner becoming unavailable', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {});
  const session = new f.Session('A', 1, false, () => {}); await f.client.enter(session);
  f.cloud.video('unrelated', 1); session.setSurfaceView('video-A');
  assert.equal(f.calls.some(c => c[0] === 'startRemote'), false);
  f.cloud.video('host', 1); session.setSurfaceView('video-B');
  assert.deepEqual(f.calls.filter(c => c[0] === 'updateRemote'), [['updateRemote', 'host', 0, 'video-B']]);
  session.setSurfaceView(''); f.cloud.video('host', 1);
  assert.equal(f.calls.filter(c => c[0] === 'startRemote').length, 1);
  session.setSurfaceView('video-C'); f.cloud.video('host', 0); session.setSurfaceView('video-D');
  assert.equal(f.calls.filter(c => c[0] === 'startRemote').length, 2);
  f.cloud.video('host', 1);
  assert.deepEqual(f.calls.filter(c => c[0] === 'startRemote').at(-1), ['startRemote', 'host', 0, 'video-D']);
  await f.client.leave(session);
  assert.equal(f.calls.filter(c => c[0] === 'stopRemote').length, 3);
});

test('release removes only its media callback, suppresses late callbacks, and never owns the shared room lifecycle', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {});
  const foreign = {}; f.cloud.addCallback(foreign);
  const events = []; const session = new f.Session('A', 1, false, e => events.push(e));
  session.setSurfaceView('video-A'); await f.client.enter(session); f.cloud.video('host', 1);
  const callback = [...f.cloud.callbacks].find(c => c !== foreign);
  await f.client.leave(session); const count = events.length; const starts = f.calls.filter(c => c[0] === 'startRemote').length;
  callback.onUserVideoAvailable('host', 1); callback.onError(-1, 'late'); session.setSurfaceView('late');
  assert.equal(events.length, count); assert.equal(f.calls.filter(c => c[0] === 'startRemote').length, starts);
  assert.equal(f.cloud.callbacks.size, 1); assert.equal(f.cloud.callbacks.has(foreign), true);
  assert.deepEqual(f.calls.filter(c => c[0] === 'stopRemote'), [['stopRemote', 'host', 0]]);
  assert.equal(f.calls.some(c => ['enterRoom', 'exitRoom', 'destroyShared', 'play', 'stop'].includes(c[0])), false);
});

test('renderer retains a unique ready Surface across room sessions and ignores readiness after destruction', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {}); f.client.setForeground(true);
  const view = new f.View(); const other = new f.View(); view.attach();
  assert.notEqual(view.surfaceViewId, other.surfaceViewId);
  view.setSurfaceReady(other.surfaceViewId, true);
  view.call('sdkJoin', JSON.stringify({ generation: 1, liveId: 'A' }), null); await tick(); f.cloud.video('host', 1);
  assert.equal(f.calls.some(c => c[0] === 'startRemote'), false);
  view.setSurfaceReady(view.surfaceViewId, true);
  view.call('sdkLeave', JSON.stringify({ generation: 1 }), null); await tick();
  view.call('sdkJoin', JSON.stringify({ generation: 2, liveId: 'B' }), null); await tick();
  assert.equal(f.calls.filter(c => c[0] === 'startRemote').length, 1);
  f.cloud.video('host', 1);
  assert.deepEqual(f.calls.filter(c => c[0] === 'startRemote').at(-1), ['startRemote', 'host', 0, view.surfaceViewId]);
  view.onDestroy(); await tick(); const count = f.calls.length;
  view.setSurfaceReady(view.surfaceViewId, true); f.cloud.video('host', 1);
  assert.equal(f.calls.length, count); other.onDestroy();
});

test('media bind failure reports a playback error and permits a later availability callback to retry', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {});
  const events = []; const session = new f.Session('A', 1, false, e => events.push(e));
  await f.client.enter(session); session.setSurfaceView('video-A');
  const start = f.cloud.startRemoteView;
  f.cloud.startRemoteView = () => { throw new Error('render failed'); }; f.cloud.video('host', 1);
  assert.deepEqual(events.map(e => e.type), ['playbackError']); assert.equal(events[0].generation, 1);
  f.cloud.startRemoteView = start; f.cloud.video('host', 1);
  assert.equal(f.calls.filter(c => c[0] === 'startRemote').length, 1);
  await f.client.leave(session);
});


async function joinedView(f) {
  await f.client.login(123, 'viewer', 'sig', {}); f.client.setForeground(true);
  const view = new f.View(); const events = []; view.setProp('nativeLiveEvent', e => events.push(e)); view.attach();
  view.setSurfaceReady(view.surfaceViewId, true);
  view.call('sdkJoin', JSON.stringify({ generation: 1, liveId: 'A' }), null); await tick();
  f.cloud.video('host', 1);
  return { view, events };
}

test('PiP acceptance and layout hints do not impersonate actual state; original Surface controller is supplied', async () => {
  const f = fixture(); const { view, events } = await joinedView(f);
  const changed = []; let closed = 0; const unsubscribe = f.client.observePictureInPicture(e => changed.push(e), () => closed++);
  view.call('updatePictureInPicture', JSON.stringify({ enabled: true }), null);
  let reply; view.call('enterPictureInPicture', '{}', r => reply = r); await tick();
  assert.equal(reply.accepted, true);
  assert.deepEqual(changed, [false]); assert.equal(events.some(e => e.type === 'pictureInPictureChanged'), false);
  const config = f.calls.find(c => c[0] === 'createPiP')[1];
  assert.equal(config.componentController, view.surfaceController); assert.equal(config.context.windowContext, true);
  f.pip.controllers[0].emit(f.pip.PiPState.STARTED);
  assert.deepEqual(changed, [false, true]);
  assert.equal(events.filter(e => e.type === 'pictureInPictureChanged').at(-1).enabled, true);
  assert.equal(f.calls.filter(c => c[0] === 'startRemote').length, 1);
  assert.equal(f.calls.some(c => ['enterRoom', 'exitRoom', 'newPlayer', 'updateRemote'].includes(c[0])), false);
  f.pip.controllers[0].emit(f.pip.PiPState.STOPPED);
  assert.deepEqual(changed, [false, true, false]); assert.equal(closed, 1);
  unsubscribe(); view.onDestroy(); await tick();
});

test('PiP protects entering and active background playback, but a real close drops that protection', async () => {
  const f = fixture(); const { view, events } = await joinedView(f); f.pip.startWait = deferred();
  view.call('enterPictureInPicture', '{}', null); await tick(); view.setVisible(false); f.client.setForeground(false);
  assert.equal(events.filter(e => e.type === 'visibility').at(-1).visible, true);
  f.pip.startWait.resolve(); await tick(); f.pip.controllers[0].emit(f.pip.PiPState.STARTED);
  assert.equal(events.filter(e => e.type === 'visibility').at(-1).visible, true);
  f.pip.controllers[0].emit(f.pip.PiPState.STOPPED);
  assert.equal(events.filter(e => e.type === 'visibility').at(-1).visible, false);
  view.onDestroy(); await tick();
});

test('system restore survives STOPPED before foreground and visible area restoration without reporting close', async () => {
  const f = fixture(); const { view, events } = await joinedView(f);
  let closed = 0; f.client.observePictureInPicture(() => {}, () => closed++);
  view.call('enterPictureInPicture', '{}', null); await tick(); const controller = f.pip.controllers[0]; controller.emit(2);
  view.setVisible(false); f.client.setForeground(false);
  await assert.rejects(f.client.restorePictureInPicture(), /not ready/);
  controller.emit(5); controller.emit(4);
  assert.equal(events.filter(e => e.type === 'visibility').at(-1).visible, true);
  f.client.setForeground(true);
  assert.equal(events.filter(e => e.type === 'visibility').at(-1).visible, true);
  view.setVisible(true); assert.equal(closed, 0);
  assert.equal(events.filter(e => e.type === 'pictureInPictureChanged').at(-1).enabled, false);
  assert.equal(f.calls.some(c => c[0] === 'stopRemote'), false);
  view.onDestroy(); await tick();
});

test('foreground restore stops PiP while keeping the current video and session; duplicate enter is rejected', async () => {
  const f = fixture(); const { view } = await joinedView(f);
  view.call('enterPictureInPicture', '{}', null); await tick(); f.pip.controllers[0].emit(2);
  let duplicate; view.call('enterPictureInPicture', '{}', r => duplicate = r); await tick();
  assert.equal(duplicate.accepted, false);
  await f.client.restorePictureInPicture();
  assert.equal(view.session.closed, false); assert.equal(f.calls.some(c => c[0] === 'leave' || c[0] === 'stopRemote'), false);
  view.onDestroy(); await tick();
});

test('release during PiP creation prevents a late start and logout still settles the business leave', async () => {
  const f = fixture(); const { view } = await joinedView(f); f.pip.createWait = deferred();
  let reply; view.call('enterPictureInPicture', '{}', r => reply = r); await tick();
  const loggingOut = f.client.logout(); await tick();
  assert.equal(f.calls.some(c => c[0] === 'leave'), false);
  f.pip.createWait.resolve(); await loggingOut; await tick();
  assert.equal(reply.accepted, false); assert.equal(f.calls.some(c => c[0] === 'startPiP'), false);
  assert.equal(f.calls.filter(c => c[0] === 'leave').length, 1); assert.equal(f.pip.controllers[0].listener, null);
  view.onDestroy(); await tick();
});

test('release waits for pending PiP start, then stops it before allowing the next room', async () => {
  const f = fixture(); const { view, events } = await joinedView(f); f.pip.startWait = deferred();
  let reply; view.call('enterPictureInPicture', '{}', r => reply = r); await tick();
  const oldCallback = f.pip.controllers[0].listener;
  view.call('sdkLeave', JSON.stringify({ generation: 1 }), null);
  view.call('sdkJoin', JSON.stringify({ generation: 2, liveId: 'B' }), null); await tick();
  assert.equal(f.calls.filter(c => c[0] === 'join').length, 1);
  f.pip.startWait.resolve(); await tick(); await tick();
  assert.equal(reply.accepted, false);
  const stop = f.calls.findIndex(c => c[0] === 'stopPiP'); const leave = f.calls.findIndex(c => c[0] === 'leave');
  assert.ok(stop >= 0 && leave > stop); assert.equal(f.calls.filter(c => c[0] === 'join').length, 2);
  const actualEvents = events.filter(e => e.type === 'pictureInPictureChanged').length;
  oldCallback(2, 'late');
  assert.equal(events.filter(e => e.type === 'pictureInPictureChanged').length, actualEvents);
  view.setVisible(false); f.client.setForeground(false);
  assert.equal(f.pip.controllers[0].listener, null);
  view.onDestroy(); await tick();
});

test('unsupported or failed PiP start restores normal visibility gating and permits retry', async () => {
  const f = fixture(); const { view, events } = await joinedView(f); f.pip.supported = false;
  let reply; view.call('enterPictureInPicture', '{}', r => reply = r); await tick(); assert.equal(reply.accepted, false);
  f.pip.supported = true; f.pip.startWait = deferred();
  view.call('enterPictureInPicture', '{}', r => reply = r); await tick();
  f.pip.startWait.reject(new Error('denied')); await tick();
  assert.equal(reply.accepted, false);
  view.setVisible(false); assert.equal(events.filter(e => e.type === 'visibility').at(-1).visible, true);
  view.setVisible(true); f.pip.startWait = null;
  view.call('enterPictureInPicture', '{}', r => reply = r); await tick(); assert.equal(reply.accepted, true);
  view.onDestroy(); await tick();
});


test('a PiP stop failure still settles business leave, removes its listener, and blocks unsafe room reuse', async () => {
  const f = fixture(); const { view } = await joinedView(f);
  view.call('enterPictureInPicture', '{}', null); await tick(); const controller = f.pip.controllers[0]; controller.emit(2);
  controller.stopPiP = async () => { throw new Error('stop failed'); };
  let reply; view.call('sdkLeave', JSON.stringify({ generation: 1 }), r => reply = r); await tick();
  assert.equal(reply.success, false); assert.equal(f.calls.filter(c => c[0] === 'leave').length, 1);
  assert.equal(controller.listener, null); assert.equal(f.client.isReady(), false);
  view.onDestroy(); await tick();
});


test('late PiP start with failed stop cannot silently allow the next room', async () => {
  const f = fixture(); const { view } = await joinedView(f); f.pip.startWait = deferred();
  view.call('enterPictureInPicture', '{}', null); await tick();
  const controller = f.pip.controllers[0]; controller.stopPiP = async () => { throw new Error('stop failed'); };
  let reply; view.call('sdkLeave', JSON.stringify({ generation: 1 }), r => reply = r); await tick();
  f.pip.startWait.resolve(); await tick(); await tick();
  assert.equal(reply.success, false); assert.equal(f.client.isReady(), false);
  assert.equal(f.calls.filter(c => c[0] === 'leave').length, 1);
  view.onDestroy(); await tick();
});


test('full watch retains one join through background and early foreground restoration, while preview still stops', async () => {
  const f = fixture(); const { view, events } = await joinedView(f); const session = view.session;
  view.setVisible(false); f.client.setForeground(false); await tick();
  assert.equal(events.filter(e => e.type === 'visibility').at(-1).visible, true);
  assert.equal(f.calls.filter(c => c[0] === 'join').length, 1); assert.equal(f.calls.some(c => c[0] === 'leave'), false);
  f.client.setForeground(true); await tick();
  assert.equal(events.filter(e => e.type === 'visibility').at(-1).visible, true);
  view.setVisible(true); assert.equal(view.session, session);
  assert.equal(f.calls.filter(c => c[0] === 'join').length, 1);
  view.onDestroy(); await tick(); assert.equal(f.calls.filter(c => c[0] === 'leave').length, 1);
  const preview = new f.View(); preview.attach(); const previewEvents = [];
  preview.setProp('nativeLiveEvent', e => {
    previewEvents.push(e);
    if (e.type === 'visibility' && !e.visible && preview.session) preview.call('sdkStopPreview', JSON.stringify({ generation: 2 }), null);
  });
  preview.call('sdkPreview', JSON.stringify({ generation: 2, liveId: 'preview' }), null); await tick();
  f.client.setForeground(false); await tick();
  assert.equal(previewEvents.filter(e => e.type === 'visibility').at(-1).visible, false);
  assert.equal(f.calls.filter(c => c[0] === 'stop').length, 1); assert.equal(preview.session, null);
  let reply; preview.call('sdkJoin', JSON.stringify({ generation: 3, liveId: 'B' }), r => reply = r); await tick();
  assert.equal(reply.success, false); assert.equal(f.calls.filter(c => c[0] === 'join').length, 1);
  preview.onDestroy();
});

test('only the current first PiP STARTED backgrounds the ability and restore never backgrounds again', async () => {
  const f = fixture(); const { view } = await joinedView(f);
  const unsubscribe = f.client.observePictureInPicture(() => {}, () => {});
  view.call('enterPictureInPicture', '{}', null); await tick(); const controller = f.pip.controllers[0];
  assert.equal(f.calls.some(c => c[0] === 'moveBackground'), false);
  controller.emit(2); controller.emit(2); await tick();
  assert.equal(f.calls.filter(c => c[0] === 'moveBackground').length, 1);
  controller.emit(5); controller.emit(2); controller.emit(4); await tick();
  assert.equal(f.calls.filter(c => c[0] === 'moveBackground').length, 1);
  assert.equal(view.session.closed, false); unsubscribe(); view.onDestroy(); await tick();
});

test('failure to background reclaims PiP as restoration without closing or rejoining the room', async () => {
  const f = fixture(); const { view, events } = await joinedView(f); f.ability.moveError = new Error('not supported');
  let closed = 0; f.client.observePictureInPicture(() => {}, () => closed++);
  view.call('enterPictureInPicture', '{}', null); await tick(); f.pip.controllers[0].emit(2); await tick();
  assert.equal(f.calls.filter(c => c[0] === 'stopPiP').length, 1);
  assert.equal(closed, 0); assert.equal(view.session.closed, false);
  assert.equal(events.filter(e => e.type === 'pictureInPictureChanged').at(-1).enabled, false);
  assert.equal(f.calls.filter(c => c[0] === 'join').length, 1); assert.equal(f.calls.some(c => c[0] === 'leave'), false);
  view.onDestroy(); await tick();
});

test('a late background rejection keeps PiP when the owner is already in background and Main is foreground', async () => {
  const f = fixture(); const main = scopedContext(f, 'Main'); const live = scopedContext(f, 'Audience');
  f.client.setForeground(true, live);
  const { view } = await joinedView(f); view.hostContext = live; f.ability.moveWait = deferred();
  view.call('enterPictureInPicture', '{}', null); await tick(); f.pip.controllers[0].emit(2);
  f.client.setForeground(false, live); f.client.setForeground(true, main);
  f.ability.moveWait.reject(new Error('owner is no longer foreground')); await tick();
  assert.equal(f.calls.filter(c => c[0] === 'stopPiP').length, 0);
  assert.equal(view.session.closed, false); assert.equal(f.calls.some(c => c[0] === 'leave'), false);
  view.onDestroy(); await tick();
});

test('real PiP close leaves once and cannot rejoin from stale visible or foreground callbacks before a new configuration', async () => {
  const f = fixture(); const { view, events } = await joinedView(f);
  view.call('enterPictureInPicture', '{}', null); await tick(); const controller = f.pip.controllers[0]; controller.emit(2);
  controller.emit(4); await tick();
  view.call('sdkLeave', JSON.stringify({ generation: 1 }), null); await tick();
  f.client.setForeground(false); f.client.setForeground(true); view.setVisible(true);
  assert.equal(events.filter(e => e.type === 'visibility').at(-1).visible, false);
  let reply; view.call('sdkJoin', JSON.stringify({ generation: 2, liveId: 'A' }), r => reply = r); await tick();
  assert.equal(reply.success, false); assert.equal(f.calls.filter(c => c[0] === 'join').length, 1);
  assert.equal(f.calls.filter(c => c[0] === 'leave').length, 1);
  view.setProp('room', JSON.stringify({ liveId: 'B' }));
  view.call('sdkJoin', JSON.stringify({ generation: 3, liveId: 'B' }), r => reply = r); await tick();
  assert.equal(reply.success, true); view.onDestroy(); await tick();
});


test('returning through the app icon reclaims only its still-started PiP and a late background completion cannot repeat the move', async () => {
  const f = fixture(); const { view, events } = await joinedView(f); f.ability.moveWait = deferred();
  let closed = 0; f.client.observePictureInPicture(() => {}, () => closed++);
  view.call('enterPictureInPicture', '{}', null); await tick(); const controller = f.pip.controllers[0]; controller.emit(2);
  assert.equal(f.calls.filter(c => c[0] === 'stopPiP').length, 0);
  f.client.setForeground(false); view.setVisible(false); await tick();
  f.client.setForeground(true); await tick();
  assert.equal(f.calls.filter(c => c[0] === 'stopPiP').length, 1);
  assert.equal(events.filter(e => e.type === 'pictureInPictureChanged').at(-1).enabled, false);
  assert.equal(events.filter(e => e.type === 'visibility').at(-1).visible, true);
  f.ability.moveWait.resolve(); await tick(); view.setVisible(true); f.client.setForeground(true);
  assert.equal(f.calls.filter(c => c[0] === 'moveBackground').length, 1);
  assert.equal(f.calls.filter(c => c[0] === 'stopPiP').length, 1);
  assert.equal(closed, 0); assert.equal(view.session.closed, false);
  assert.equal(f.calls.filter(c => c[0] === 'join').length, 1); assert.equal(f.calls.some(c => c[0] === 'leave'), false);
  view.onDestroy(); await tick();
});


test('a late restore rejection from an old PiP controller cannot close a newer PiP on the same room session', async () => {
  const f = fixture(); const { view } = await joinedView(f);
  view.call('enterPictureInPicture', '{}', null); await tick(); const old = f.pip.controllers[0]; old.emit(2);
  const stopping = deferred(); old.stopPiP = () => stopping.promise;
  f.client.setForeground(false); view.setVisible(false); f.client.setForeground(true); await tick();
  old.emit(4); view.setVisible(true);
  view.call('enterPictureInPicture', '{}', null); await tick(); const current = f.pip.controllers[1]; current.emit(2);
  stopping.reject(new Error('old failure')); await tick();
  assert.equal(view.session.closed, false); assert.equal(f.calls.some(c => c[0] === 'leave'), false);
  view.onDestroy(); await tick();
});


function scopedContext(f, name) {
  return { ...f.windowContext, abilityInfo: { bundleName: 'test.app', moduleName: 'entry', name } };
}

test('singleton Ability foreground is keyed by metadata and does not depend on Context object identity', () => {
  const f = fixture(); const main = scopedContext(f, 'Main'); const live = scopedContext(f, 'Audience');
  const liveWindowContext = scopedContext(f, 'Audience'); assert.notEqual(live, liveWindowContext);
  f.client.setForeground(true, main);
  assert.equal(f.client.isForeground(main), true); assert.equal(f.client.isForeground(live), false);
  f.client.setForeground(true, live); f.client.setForeground(false, main);
  assert.equal(f.client.isForeground(main), false); assert.equal(f.client.isForeground(liveWindowContext), true);
  f.client.setForeground(true, main); f.client.setForeground(false, liveWindowContext);
  assert.equal(f.client.isForeground(main), true); assert.equal(f.client.isForeground(live), false);
});

test('legacy foreground API still drives an unregistered single Ability and cannot override registered scopes', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {});
  const live = scopedContext(f, 'Audience'); const view = new f.View(); view.hostContext = live; view.attach();
  f.client.setForeground(true);
  assert.equal(f.client.isForeground(), true); assert.equal(f.client.isForeground(live), true);
  let reply; view.call('sdkJoin', JSON.stringify({ generation: 1, liveId: 'A' }), r => reply = r); await tick();
  assert.equal(reply.success, true);
  f.client.setForeground(false, live); f.client.setForeground(true);
  assert.equal(f.client.isForeground(), true); assert.equal(f.client.isForeground(live), false);
  view.onDestroy(); await tick();
});

test('Main foreground leaves live PiP floating, and only the owner Ability returning to foreground restores it', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {});
  const main = scopedContext(f, 'Main'); const live = scopedContext(f, 'Audience');
  f.client.setForeground(false, main); f.client.setForeground(true, live);
  const view = new f.View(); view.hostContext = scopedContext(f, 'Audience'); const events = [];
  view.setProp('nativeLiveEvent', e => events.push(e)); view.attach(); view.setSurfaceReady(view.surfaceViewId, true);
  let reply; view.call('sdkJoin', JSON.stringify({ generation: 1, liveId: 'A' }), r => reply = r); await tick();
  assert.equal(reply.success, true); assert.equal(f.client.isForeground(), false);
  view.call('enterPictureInPicture', '{}', null); await tick(); const controller = f.pip.controllers[0]; controller.emit(2);
  f.client.setForeground(false, live); view.setVisible(false); f.client.setForeground(true, main); await tick();
  assert.equal(f.calls.some(c => c[0] === 'stopPiP'), false);
  assert.equal(events.filter(e => e.type === 'pictureInPictureChanged').at(-1).enabled, true);
  await assert.rejects(f.client.restorePictureInPicture(), /not ready/);
  f.client.setForeground(false, main); await tick(); assert.equal(f.calls.some(c => c[0] === 'stopPiP'), false);
  f.client.setForeground(true, live); await tick();
  assert.equal(f.calls.filter(c => c[0] === 'stopPiP').length, 1);
  assert.equal(events.filter(e => e.type === 'pictureInPictureChanged').at(-1).enabled, false);
  assert.equal(view.session.closed, false); assert.equal(f.calls.filter(c => c[0] === 'join').length, 1);
  assert.equal(f.calls.some(c => c[0] === 'leave'), false);
  view.onDestroy(); await tick();
});


test('static PiP restore delegates to its foreground owner even when the legacy global foreground is false', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {});
  const live = scopedContext(f, 'Audience'); f.client.setForeground(true, live);
  const view = new f.View(); view.hostContext = scopedContext(f, 'Audience'); view.attach(); view.setSurfaceReady(view.surfaceViewId, true);
  view.call('sdkJoin', JSON.stringify({ generation: 1, liveId: 'A' }), null); await tick();
  view.call('enterPictureInPicture', '{}', null); await tick(); f.pip.controllers[0].emit(2);
  assert.equal(f.client.isForeground(), false);
  await f.client.restorePictureInPicture();
  assert.equal(f.calls.filter(c => c[0] === 'stopPiP').length, 1); assert.equal(view.session.closed, false);
  view.onDestroy(); await tick();
});
