// Prints the text of every QR code in an image. Used to read the phone-setup address off a TV
// screenshot, since the token is never logged:
//   swift tools/qr-from-screenshot.swift shot.png
import CoreImage
import Foundation

let url = URL(fileURLWithPath: CommandLine.arguments[1])
guard let image = CIImage(contentsOf: url),
      let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
else { exit(1) }
for case let code as CIQRCodeFeature in detector.features(in: image) {
    print(code.messageString ?? "")
}
