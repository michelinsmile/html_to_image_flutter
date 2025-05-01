import Flutter
import UIKit
import WebKit

public class HtmlToImageFlutterPlugin: NSObject, FlutterPlugin {
    var webView: WKWebView!
    var urlObservation: NSKeyValueObservation?

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "html_to_image_flutter", binaryMessenger: registrar.messenger())
        let instance = HtmlToImageFlutterPlugin()
        registrar.addMethodCallDelegate(instance, channel: channel)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let arguments = call.arguments as? [String: Any],
              let content = arguments["content"] as? String else {
            result(FlutterError(code: "INVALID_ARGUMENTS", message: "Missing 'content'", details: nil))
            return
        }

        let delay = arguments["delay"] as? Double ?? 200.0

        switch call.method {
        case "convertToImage":
            self.webView = WKWebView(frame: .zero)
            self.webView.isHidden = true
            self.webView.tag = 100

            // Ensure viewport for full width scaling
            let htmlWithViewport = """
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <style>
                body { margin: 0; padding: 0; }
              </style>
            </head>
            <body>
              \(content)
            </body>
            </html>
            """

            self.webView.loadHTMLString(htmlWithViewport, baseURL: nil)

            var bytes = FlutterStandardTypedData(bytes: Data())

            urlObservation = webView.observe(\.isLoading, changeHandler: { (webView, change) in
                DispatchQueue.main.asyncAfter(deadline: .now() + (delay / 1000)) {
                    if #available(iOS 11.0, *) {
                        self.webView.scrollView.contentInsetAdjustmentBehavior = .never

                        let contentSize = self.webView.scrollView.contentSize
                        let width = contentSize.width
                        let height = contentSize.height

                        self.webView.frame = CGRect(x: 0, y: 0, width: width, height: height)

                        let configuration = WKSnapshotConfiguration()
                        configuration.rect = CGRect(origin: .zero, size: contentSize)

                        self.webView.takeSnapshot(with: configuration) { (image, error) in
                            guard let image = image, let data = image.jpegData(compressionQuality: 1.0) else {
                                result(bytes)
                                self.dispose()
                                return
                            }
                            bytes = FlutterStandardTypedData(bytes: data)
                            result(bytes)
                            self.dispose()
                        }
                    } else {
                        result(bytes)
                        self.dispose()
                    }
                }
            })
            break

        default:
            result(FlutterMethodNotImplemented)
        }
    }

    func dispose() {
        if let viewWithTag = self.webView.viewWithTag(100) {
            viewWithTag.removeFromSuperview()

            if #available(iOS 9.0, *) {
                WKWebsiteDataStore.default().fetchDataRecords(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes()) { records in
                    records.forEach { record in
                        WKWebsiteDataStore.default().removeData(ofTypes: record.dataTypes, for: [record], completionHandler: {})
                    }
                }
            }
        }
        self.webView = nil
    }
}
