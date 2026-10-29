import { KuiklyRenderBaseView } from '@kuikly-open/render';
import { AtomicLiveViewImpl } from '@tencentcloud/atomicxcore';
import { GycLiveView } from './GycLiveView';
import { NativeLiveSession } from './GycLiveClient';

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
      ForEach(this.renderView.session ? [this.renderView.session] : [], (session: NativeLiveSession) => {
        AtomicLiveViewImpl(this.renderView.playerViewParameters(session))
          .width('100%').height('100%')
          .aspectRatio(this.renderView.pictureInPictureAspect)
      }, (session: NativeLiveSession) => `${session.generation}`)
    }.width('100%').height('100%')
    .onVisibleAreaChange([0, 1], (visible: boolean) => this.renderView.setVisible(visible))
  }
}
