import GoogleCast
import SwiftUI
import AVKit

enum CastSetup {
    /// Start Google Cast. Het receiver-ID komt uit Info.plist (instelbaar via
    /// FAMILY7_CAST_RECEIVER_ID); standaard de Default Media Receiver, die elke
    /// Chromecast en Google TV kent.
    static func start() {
        let receiver = Bundle.main.object(forInfoDictionaryKey: "Family7CastReceiverID") as? String
        let criteria = GCKDiscoveryCriteria(applicationID: receiver?.nonEmpty ?? kGCKDefaultMediaReceiverApplicationID)
        let options = GCKCastOptions(discoveryCriteria: criteria)
        // De volumeknoppen van de telefoon regelen het volume van de tv.
        options.physicalVolumeButtonsWillControlDeviceVolume = true
        // Pas zoeken na de eerste tik op de Cast-knop: dan vraagt iOS pas om
        // toegang tot het lokale netwerk als iemand echt wil casten.
        options.startDiscoveryAfterFirstTapOnCastButton = true
        options.suspendSessionsWhenBackgrounded = false
        GCKCastContext.setSharedInstanceWith(options)
        GCKCastContext.sharedInstance().useDefaultExpandedMediaControls = false

        let style = GCKUIStyle.sharedInstance()
        style.castViews.iconTintColor = .white
        style.castViews.deviceControl.connectionController.backgroundColor = UIColor(Color.family7Background)
        style.apply()
    }
}

/// De standaard Cast-knop van Google; opent de apparatenkiezer.
struct CastButton: UIViewRepresentable {
    func makeUIView(context: Context) -> GCKUICastButton {
        let button = GCKUICastButton(frame: CGRect(x: 0, y: 0, width: 28, height: 28))
        button.tintColor = .white
        return button
    }
    func updateUIView(_ uiView: GCKUICastButton, context: Context) {}
}

/// De AirPlay-knop: kies een Apple TV of AirPlay-tv.
struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.prioritizesVideoDevices = true
        picker.tintColor = .white
        picker.activeTintColor = UIColor(Color.family7Red)
        return picker
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}

/// AirPlay en Cast naast elkaar, voor in een werkbalk.
struct TVButtons: View {
    var body: some View {
        HStack(spacing: 4) {
            AirPlayButton().frame(width: 32, height: 32)
            CastButton().frame(width: 32, height: 32)
        }
    }
}
