import Foundation
import Capacitor
import UserNotifications

/// The page's way in to FenceManager: window.Capacitor.registerPlugin('ShipFence').
@objc(ShipFencePlugin)
public class ShipFencePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "ShipFencePlugin"
    public let jsName = "ShipFence"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "enable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "status", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setShips", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "position", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "takeTapped", returnType: CAPPluginReturnPromise)
    ]

    override public func load() {
        NotificationCenter.default.addObserver(self, selector: #selector(tapped(_:)), name: .shipFenceTapped, object: nil)
    }

    @objc private func tapped(_ note: Notification) {
        guard let id = note.userInfo?["mmsi"] as? String else { return }
        // kept until the page is listening, in case the tap launched the app
        notifyListeners("tapped", data: ["mmsi": id], retainUntilConsumed: true)
    }

    /// Asks for notifications, then location ("While using", then "Always").
    @objc func enable(_ call: CAPPluginCall) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, _ in
            DispatchQueue.main.async {
                FenceManager.shared.requestAlways()
                call.resolve(["notifications": granted, "location": FenceManager.shared.authStatus()])
            }
        }
    }

    @objc func status(_ call: CAPPluginCall) {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            DispatchQueue.main.async {
                call.resolve([
                    "notifications": settings.authorizationStatus == .authorized,
                    "location": FenceManager.shared.authStatus(),
                    "watching": FenceManager.shared.watchingCount
                ])
            }
        }
    }

    @objc func setShips(_ call: CAPPluginCall) {
        let raw = call.getArray("ships") ?? []
        var list: [[String: Any]] = []
        for item in raw {
            guard let obj = item as? JSObject else { continue }
            var ship: [String: Any] = [:]
            for (key, value) in obj where !(value is NSNull) { ship[key] = value }
            list.append(ship)
        }
        DispatchQueue.main.async {
            FenceManager.shared.setShips(list)
            call.resolve(["watching": list.count])
        }
    }

    @objc func position(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            FenceManager.shared.currentPosition { result in
                switch result {
                case .success(let loc):
                    call.resolve(["lat": loc.coordinate.latitude, "lon": loc.coordinate.longitude, "accuracy": loc.horizontalAccuracy])
                case .failure(let error):
                    call.reject(error.localizedDescription)
                }
            }
        }
    }

    @objc func takeTapped(_ call: CAPPluginCall) {
        if let id = FenceManager.shared.takeTapped() { call.resolve(["mmsi": id]) } else { call.resolve([:]) }
    }
}
