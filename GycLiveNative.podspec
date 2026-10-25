Pod::Spec.new do |s|
  s.name = 'GycLiveNative'
  s.version = '1.0.1'
  s.summary = '腾讯 AtomicX iOS 原生直播观看、IM 与系统 PiP 适配'
  s.homepage = 'https://github.com/gycrosskit/live-sdk'
  s.license = { :type => 'Apache-2.0', :file => 'LICENSE' }
  s.author = 'GY CrossKit'
  s.source = { :git => 'https://github.com/gycrosskit/live-sdk.git', :tag => "native-#{s.version}" }
  s.ios.deployment_target = '15.0'
  s.swift_version = '5.9'
  s.static_framework = true
  s.default_subspecs = 'Core'
  s.frameworks = 'UIKit', 'Foundation'
  s.subspec 'Core' do |core|
    core.source_files = 'ios/Sources/GycLiveNative/*.swift'
    core.frameworks = 'Combine'
    core.dependency 'AtomicXCore', '4.3.9'
    core.dependency 'RTCRoomEngine/Professional', '4.3.9'
    core.dependency 'TXIMSDK_Plus_iOS_XCFramework', '9.1.7818'
  end
  s.subspec 'Renderer' do |renderer|
    renderer.source_files = 'ios/Sources/GycLiveRenderer/*.swift'
    renderer.dependency 'OpenKuiklyIOSRender', '2.29.1'
  end
end
