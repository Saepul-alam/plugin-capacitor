Pod::Spec.new do |s|
  s.name         = 'PluginSecure'
  s.version      = '1.0.0'
  s.summary      = 'Security plugin for Capacitor - Fake GPS detection & verified camera'
  s.license      = 'MIT'
  s.homepage     = 'https://github.com/Saepul-alam/plugin-capacitor'
  s.author       = { 'Saepul Alam' => 'saepul.alam@example.com' }
  s.source       = { :git => 'https://github.com/Saepul-alam/plugin-capacitor.git', :tag => s.version.to_s }
  s.source_files = 'Sources/**/*.{swift,h,m}'
  s.ios.deployment_target = '13.0'
  s.dependency 'Capacitor'
  s.swift_version = '5.1'
  s.resource_bundles = {
    'PluginSecureResources' => ['Resources/**/*']
  }
end