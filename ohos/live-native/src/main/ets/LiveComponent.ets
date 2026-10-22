import { KuiklyRenderBaseView } from '@kuikly-open/render';
import { AtomicLiveViewImpl } from '@tencentcloud/atomicxcore';
import { GycLiveView } from './GycLiveView';

@Builder
export function createGycLiveView(view: KuiklyRenderBaseView) {
  GycLiveHost({ renderView: view as GycLiveView })
}

@Component
struct GycLiveHost {
  @ObjectLink renderView: GycLiveView;
  aboutToAppear(): void { this.renderView.attach(); }
  aboutToDisappear(): void { this.renderView.setVisible(false); }
  build() {
    Stack() {
      if (this.renderView.session) {
        // shortcut: 5.2.2 必须用内部导出的 ArkUI 渲染节点；升级腾讯 SDK 时核对兼容性。
        AtomicLiveViewImpl({ view: this.renderView.session.view })
      }
    }.width('100%').height('100%')
    .onVisibleAreaChange([0, 1], (visible: boolean) => this.renderView.setVisible(visible))
  }
}
