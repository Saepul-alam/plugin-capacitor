Pod::Spec.new do |s|
  s.name         = 'AlPluginsecure'
  s.version      = '1.0.2'
  s.summary      = 'Security plugin for Capacitor - Fake GPS detection, verified camera, native error UI & state preservation'
  s.license      = 'MIT'
  s.homepage     = 'https://github.com/Saepul-alam/plugin-capacitor'
  s.author       = { 'Saepul Alam' => 'saepul-alam' }
  s.source       = { :git => 'https://github.com/Saepul-alam/plugin-capacitor.git', :tag => s.version.to_s }
  s.source_files = 'src/ios/Sources/**/*.{swift,h,m}'
  s.ios.deployment_target = '12.0'
  s.dependency 'Capacitor'
  s.swift_version = '5.1'
end