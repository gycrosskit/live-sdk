Pod::Spec.new do |s|
  s.name = 'GycLiveNative'
  s.version = '0.2.1-rc.1'
  s.summary = '腾讯 AtomicX iOS 原生直播观看、IM 与系统 PiP 适配'
  s.homepage = 'https://github.com/gycrosskit/live-sdk'
  s.license = { :type => 'Apache-2.0', :file => 'LICENSE' }
  s.author = 'GY CrossKit'
  s.source = { :git => 'https://github.com/gycrosskit/live-sdk.git', :tag => s.version.to_s }
  s.ios.deployment_target = '15.0'
  s.swift_version = '5.9'
  s.static_framework = true
  s.source_files = 'ios/Sources/GycLiveNative/*.swift'
  s.frameworks = 'UIKit', 'Foundation', 'Combine'
  s.dependency 'AtomicXCore', '4.3.9'
  s.dependency 'RTCRoomEngine/Professional', '4.3.9'
  s.dependency 'TXIMSDK_Plus_iOS_XCFramework', '9.1.7818'
end
