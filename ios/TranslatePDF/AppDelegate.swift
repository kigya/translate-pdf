import UIKit

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
  private var backgroundSessionCompletionHandler: (() -> Void)?

  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(backgroundSessionDidFinishEvents),
      name: NSNotification.Name("TranslatePDFBackgroundSessionDidFinishEvents"),
      object: nil
    )
    return true
  }

  func application(
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
