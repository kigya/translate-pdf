Pod::Spec.new do |spec|
  spec.name = 'TranslateCore'
  spec.version = '1.0.0'
  spec.summary = 'Private local PDF translation core for TranslatePDF.'
  spec.homepage = 'https://example.invalid/translatepdf-local-core'
  spec.license = { :type => 'Proprietary', :text => 'Private application component.' }
  spec.author = { 'TranslatePDF' => 'local' }
  spec.source = { :git => 'https://example.invalid/translatepdf-local-core.git', :tag => spec.version.to_s }
  spec.platform = :ios, '17.0'
  spec.swift_version = '5.0'
  spec.source_files = [
    'TranslatePDF/PdfTranslatorModule.swift',
    'TranslatePDF/PdfTranslatorBridge.m',
    'TranslatePDF/TranslateCoreBridge.{h,mm}',
    'TranslatePDF/TranslatorStore.swift',
    'TranslatePDF/ModelCatalog.swift',
    'TranslatePDF/ModelDownloadCoordinator.swift'
  ]
  spec.dependency 'React-Core'
  spec.resource_bundles = {
    'TranslatePDFFont' => ['../assets/fonts/NotoSans-Regular.ttf'],
    'TranslatePDFLicenses' => ['TranslateCore/Licenses/*']
  }
  spec.vendored_frameworks = ['Frameworks/TranslateCore.xcframework', 'Frameworks/PDFium.xcframework']
  spec.pod_target_xcconfig = {
    'HEADER_SEARCH_PATHS' => '$(inherited) "${PODS_TARGET_SRCROOT}/../native"',
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++17',
    'DEFINES_MODULE' => 'YES'
  }
  spec.user_target_xcconfig = { 'OTHER_LDFLAGS' => '$(inherited) -lc++ -framework Accelerate' }
end
