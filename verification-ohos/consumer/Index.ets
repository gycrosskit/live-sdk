import { GycLiveClient, GycLiveView, KuiklyRenderBaseView } from '@gycrosskit/live-native';
import { ViewsRegisterEntry } from '@kuikly-open/render';
import { common } from '@kit.AbilityKit';

// 只编译公开接线合同，不执行账号登录、View 挂载或设备播放。
export function registerLiveView(): void {
  ViewsRegisterEntry.registerViewCreator(GycLiveView.VIEW_NAME, () => new GycLiveView());
}
export function createLiveView(): KuiklyRenderBaseView { return new GycLiveView(); }
export async function prepareLive(context: common.Context, sdkAppId: number, userId: string,
  userSig: string): Promise<void> {
  await GycLiveClient.login(sdkAppId, userId, userSig, context);
  GycLiveClient.setForeground(true);
}
export async function releaseLive(): Promise<void> {
  GycLiveClient.setForeground(false);
  await GycLiveClient.logout();
}
