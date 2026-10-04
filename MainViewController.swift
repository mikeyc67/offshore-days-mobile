import UIKit
import Capacitor

/// Capacitor's page view, with our own ShipFence plugin added.
class MainViewController: CAPBridgeViewController {
    override open func capacitorDidLoad() {
        bridge?.registerPluginInstance(ShipFencePlugin())
    }
}
