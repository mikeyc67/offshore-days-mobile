import Foundation
import CoreLocation
import UserNotifications

extension Notification.Name {
    static let shipFenceTapped = Notification.Name("ShipFenceTapped")
}

/// Watches the spots where your saved ships are alongside (sent over from the app) and posts a
/// notification when you arrive at one. iOS keeps watching these regions even when the app is closed,
/// and relaunches the app in the background to deliver the arrival.
final class FenceManager: NSObject, CLLocationManagerDelegate, UNUserNotificationCenterDelegate {
    static let shared = FenceManager()

    private let manager = CLLocationManager()
    private let defaults = UserDefaults.standard
    private var positionWaiters: [(Result<CLLocation, Error>) -> Void] = []
    private var wantAlways = false

    /// Called once from the app delegate, on the main thread, at every launch.
    func start() {
        manager.delegate = self
        UNUserNotificationCenter.current().delegate = self
    }

    // MARK: ships to watch
    // Each: mmsi, name, port, lat, lon, radius (metres), until (seconds since 1970)

    private var ships: [[String: Any]] {
        get { defaults.array(forKey: "shipfence.ships") as? [[String: Any]] ?? [] }
        set { defaults.set(newValue, forKey: "shipfence.ships") }
    }

    var watchingCount: Int { manager.monitoredRegions.count }

    func setShips(_ list: [[String: Any]]) {
        ships = list
        for region in manager.monitoredRegions { manager.stopMonitoring(for: region) }
        guard CLLocationManager.isMonitoringAvailable(for: CLCircularRegion.self) else { return }
        let now = Date().timeIntervalSince1970
        for ship in list.prefix(20) {   // iOS watches at most 20 regions per app
            guard let id = ship["mmsi"] as? String,
                  let lat = number(ship["lat"]), let lon = number(ship["lon"]) else { continue }
            if let until = number(ship["until"]), until < now { continue }
            let radius = min(max(number(ship["radius"]) ?? 500, 100), manager.maximumRegionMonitoringDistance)
            let region = CLCircularRegion(center: CLLocationCoordinate2D(latitude: lat, longitude: lon), radius: radius, identifier: id)
            region.notifyOnEntry = true
            region.notifyOnExit = false
            manager.startMonitoring(for: region)
        }
    }

    private func number(_ v: Any?) -> Double? {
        if let d = v as? Double { return d }
        if let n = v as? NSNumber { return n.doubleValue }
        if let i = v as? Int { return Double(i) }
        return nil
    }

    // MARK: arriving

    func locationManager(_ manager: CLLocationManager, didStartMonitoringFor region: CLRegion) {
        // already standing there when the spot is added: still worth asking
        manager.requestState(for: region)
    }

    func locationManager(_ manager: CLLocationManager, didDetermineState state: CLRegionState, for region: CLRegion) {
        if state == .inside { arrived(region.identifier) }
    }

    func locationManager(_ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: Error) {}

    private func arrived(_ id: String) {
        guard let ship = ships.first(where: { ($0["mmsi"] as? String) == id }) else { return }
        if let until = number(ship["until"]), until < Date().timeIntervalSince1970 { return }
        // once a day per ship
        let day = Self.today()
        var told = defaults.dictionary(forKey: "shipfence.told") as? [String: String] ?? [:]
        if told[id] == day { return }
        told[id] = day
        defaults.set(told, forKey: "shipfence.told")

        let name = ship["name"] as? String ?? "your ship"
        let port = ship["port"] as? String ?? ""
        let content = UNMutableNotificationContent()
        content.title = "At \(name)?"
        content.body = port.isEmpty
            ? "You’re by \(name). Starting a voyage? Tap to log it."
            : "You’re by \(name) in \(port). Starting a voyage? Tap to log it."
        content.sound = .default
        content.userInfo = ["mmsi": id]
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "shipfence-\(id)", content: content, trigger: nil))
    }

    static func today() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.timeZone = TimeZone(identifier: "Europe/London")
        return f.string(from: Date())
    }

    // MARK: notifications

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
        if #available(iOS 14.0, *) { done([.banner, .sound]) } else { done([.alert, .sound]) }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler done: @escaping () -> Void) {
        if let id = response.notification.request.content.userInfo["mmsi"] as? String {
            // kept until the app asks for it, in case the page hasn't loaded yet
            defaults.set(id, forKey: "shipfence.tapped")
            NotificationCenter.default.post(name: .shipFenceTapped, object: nil, userInfo: ["mmsi": id])
        }
        done()
    }

    func takeTapped() -> String? {
        let id = defaults.string(forKey: "shipfence.tapped")
        defaults.removeObject(forKey: "shipfence.tapped")
        return id
    }

    // MARK: permission and position

    /// The location permission, asked the iOS 14+ way where available (the app also runs on iOS 13).
    private var permission: CLAuthorizationStatus {
        if #available(iOS 14.0, *) { return manager.authorizationStatus }
        return CLLocationManager.authorizationStatus()
    }

    func authStatus() -> String {
        switch permission {
        case .authorizedAlways: return "always"
        case .authorizedWhenInUse: return "whenInUse"
        case .denied: return "denied"
        case .restricted: return "restricted"
        default: return "notDetermined"
        }
    }

    /// iOS asks "While using the app" first; once that's granted it can offer "Change to Always Allow".
    func requestAlways() {
        wantAlways = true
        switch permission {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse: manager.requestAlwaysAuthorization()
        default: wantAlways = false
        }
    }

    // iOS 14 and later
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        permissionChanged()
    }

    // iOS 13
    func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        permissionChanged()
    }

    private func permissionChanged() {
        let status = permission
        if wantAlways && status == .authorizedWhenInUse {
            wantAlways = false
            manager.requestAlwaysAuthorization()
        }
        if !positionWaiters.isEmpty {
            if status == .authorizedAlways || status == .authorizedWhenInUse { manager.requestLocation() }
            else if status == .denied || status == .restricted { finishPosition(.failure(CLError(.denied))) }
        }
    }

    func currentPosition(_ done: @escaping (Result<CLLocation, Error>) -> Void) {
        positionWaiters.append(done)
        manager.desiredAccuracy = kCLLocationAccuracyBest
        switch permission {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .denied, .restricted: finishPosition(.failure(CLError(.denied)))
        default: manager.requestLocation()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if let loc = locations.last { finishPosition(.success(loc)) }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        finishPosition(.failure(error))
    }

    private func finishPosition(_ result: Result<CLLocation, Error>) {
        let waiters = positionWaiters
        positionWaiters = []
        waiters.forEach { $0(result) }
    }
}
