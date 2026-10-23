import { KuiklyRenderBaseView } from '@kuikly-open/render';
import { AtomicLiveViewImpl } from '@tencentcloud/atomicxcore';
import { GycLiveView } from './GycLiveView';

@Builder
export function createGycLiveView(view: KuiklyRenderBaseView) {
  GycLiveHost({ renderView: view as GycLiveView })
}

@Component
struct GycLiveHost {
  // Renderer 直传对象，不经过父组件的状态变量，由本节点订阅其属性变化。
  @State renderView: GycLiveView = new GycLiveView();
  aboutToAppear(): void { this.renderView.attach(); }
  aboutToDisappear(): void { this.renderView.setVisible(false); }
  build() {
    Stack() {
      if (this.renderView.session?.preview === true && this.renderView.session.view) {
        // shortcut: preview 保留 5.2.2 的 Atomic ArkUI 节点；升级腾讯 SDK 时核对兼容性。
        AtomicLiveViewImpl({ view: this.renderView.session.view })
      } else {
        XComponent({ id: this.renderView.surfaceViewId, type: XComponentType.SURFACE, libraryname: 'liteavsdk' })
          .width('100%').height('100%')
          .onLoad(() => this.renderView.setSurfaceReady(this.renderView.surfaceViewId, true))
          .onDestroy(() => this.renderView.setSurfaceReady(this.renderView.surfaceViewId, false))
      }
    }.width('100%').height('100%')
    .onVisibleAreaChange([0, 1], (visible: boolean) => this.renderView.setVisible(visible))
  }
}
