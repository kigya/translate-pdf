internal import Expo
import UIKit
import React
import ReactAppDependencyProvider

@main
class AppDelegate: ExpoAppDelegate, ExpoReactNativeFactoryProvider {
  var window: UIWindow?
  var reactNativeFactory: RCTReactNativeFactory?
  private var reactNativeDelegate: FolivelaReactNativeDelegate?
  private var backgroundSessionCompletionHandler: (() -> Void)?

  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    let delegate = FolivelaReactNativeDelegate()
    delegate.dependencyProvider = RCTAppDependencyProvider()
    reactNativeDelegate = delegate
    reactNativeFactory = ExpoReactNativeFactory(delegate: delegate)
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(backgroundSessionDidFinishEvents),
      name: NSNotification.Name("TranslatePDFBackgroundSessionDidFinishEvents"),
      object: nil
    )
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  override func application(
    _ application: UIApplication,
    handleEventsForBackgroundURLSession identifier: String,
    completionHandler: @escaping () -> Void
  ) {
    backgroundSessionCompletionHandler = completionHandler
  }

  @objc private func backgroundSessionDidFinishEvents() {
    let completion = backgroundSessionCompletionHandler
    backgroundSessionCompletionHandler = nil
    completion?()
  }
}

class FolivelaReactNativeDelegate: ExpoReactNativeFactoryDelegate {
  override func customize(_ rootView: UIView) {
    super.customize(rootView)
    rootView.backgroundColor = UIColor(red: 198.0 / 255, green: 40.0 / 255, blue: 40.0 / 255, alpha: 1)
  }
  override func sourceURL(for bridge: RCTBridge) -> URL? { bridge.bundleURL ?? bundleURL() }
  override func bundleURL() -> URL? {
    #if DEBUG
    return RCTBundleURLProvider.sharedSettings().jsBundleURL(forBundleRoot: ".expo/.virtual-metro-entry")
    #else
    return Bundle.main.url(forResource: "main", withExtension: "jsbundle")
    #endif
  }
}
