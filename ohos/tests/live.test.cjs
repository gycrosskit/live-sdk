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
  const audience = { liveAudienceState: { audienceCount: 0, audienceList: source(), messageBannedUserList: source() }, async fetchAudienceList() {}, addLiveAudienceListener() {}, removeLiveAudienceListener() {} };
  const likes = { async sendLike(count) { calls.push(['like', count]); }, addLikeListener() {}, removeLikeListener() {} };
  const live = { listeners: new Set(), join: null, leave: null,
    async joinLive(id) { calls.push(['join', id]); if (this.join) await this.join.promise; return { liveName: id, notice: '', liveOwner: { userID: 'host', userName: '', avatarURL: '' } }; },
    async leaveLive() { calls.push(['leave']); if (this.leave) await this.leave.promise; },
    addLiveListListener(value) { this.listeners.add(value); }, removeLiveListListener(value) { this.listeners.delete(value); },
  };
  class Player {
    state = { playbackStatus: 0 }; listener = null;
    setView() {} setVolume(value) { this.volume = value; }
    addListener(value) { this.listener = value; } removeListener() { this.listener = null; }
    startPlay(url) { calls.push(['play', url]); } stopPlay() { calls.push(['stop']); }
    getState() { return this.state; }
  }
  class BaseView { setProp() { return false; } onDestroy() {} }
  const sdk = { AtomicPlayer: Player, AtomicLiveController: { create() { return {}; } }, AtomicLiveView: class {}, AtomicLiveViewType: { playView: 0 },
    BarrageStore: { create: () => barrage }, BarrageType: { TEXT: 0 }, LikeStore: { create: () => likes }, LikeListener: class {},
    LiveAudienceStore: { create: () => audience }, LiveAudienceListener: class {}, LiveListStore: { shared: () => live }, LiveListListener: class {},
    LoginStore: { shared: () => login }, LoginStatus: { LOGINED: 1 }, PlayerListener: class {}, PlayerPlaybackStatus: { PLAYING: 2 }, UserProfile: class {},
    V2TIMManager: { getInstance: () => ({ getLoginUser: () => identity.user, getLoginStatus: () => identity.status }) } };
  const cache = new Map();
  function load(name) {
    if (cache.has(name)) return cache.get(name);
    const result = ts.transpileModule(fs.readFileSync(path.join(root, name + '.ets'), 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2021, experimentalDecorators: true }, reportDiagnostics: true });
    assert.equal(result.diagnostics.filter(d => d.category === ts.DiagnosticCategory.Error).length, 0);
    const module = { exports: {} };
    vm.runInNewContext(result.outputText, { module, exports: module.exports, require(name) {
      if (name === '@tencentcloud/atomicxcore') return sdk;
      if (name === '@kit.AbilityKit') return {};
      if (name === '@kuikly-open/render') return { KuiklyRenderBaseView: BaseView };
      return load(name.replace('./', ''));
    }, Observed: value => value, setInterval(fn) { const id = ++nextTimer; timers.set(id, fn); return id; }, clearInterval(id) { timers.delete(id); },
      setTimeout(fn) { const id = ++nextTimer; timers.set(id, fn); return id; }, clearTimeout(id) { timers.delete(id); },
      Map, Set, Number, Promise, Error, encodeURIComponent }, { filename: name + '.ets' });
    cache.set(name, module.exports); return module.exports;
  }
  const { GycLiveClient: client, NativeLiveSession: Session } = load('GycLiveClient');
  return { client, Session, View: load('GycLiveView').GycLiveView, calls, timers, identity, login, live, barrage, audience, accountExpired() { accountEvent?.(); } };
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

test('released barrage cannot report success and old events stop reaching the observer', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {});
  const events = []; const session = new f.Session('A', 1, false, event => events.push(event));
  await f.client.enter(session); session.observe();
  f.barrage.pending = deferred(); const sending = session.sendBarrage('hello'); const failed = assert.rejects(sending, /released/);
  const listener = session.player.listener;
  await f.client.leave(session); const count = events.length;
  listener.liveEnded(); f.barrage.pending.resolve(); await failed;
  assert.equal(events.length, count); assert.equal(f.barrage.barrageState.messageList.listeners.size, 0);
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

test('join failure still leaves; a throwing player cleanup does not skip SDK leave', async () => {
  const f = fixture(); await f.client.login(123, 'viewer', 'sig', {});
  f.live.join = deferred(); const a = new f.Session('A', 1, false, () => {});
  const joining = f.client.enter(a); const failed = assert.rejects(joining, /bad room/);
  await tick(); f.live.join.reject(new Error('bad room')); await failed;
  assert.equal(f.calls.filter(c => c[0] === 'leave').length, 1);
  f.live.join = null;
  const b = new f.Session('B', 2, false, () => {}); await f.client.enter(b);
  b.player.stopPlay = () => { throw new Error('native stop'); };
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
