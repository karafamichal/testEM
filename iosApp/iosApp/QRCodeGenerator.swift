import Foundation
import CoreImage.CIFilterBuiltins
import UIKit

enum QRCodeGenerator {
    static func makeImage(from payload: String) -> UIImage? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(payload.utf8)
        filter.correctionLevel = "H"
        guard let outputImage = filter.outputImage else { return nil }
        let scale = max(1, Int(QRDaemonConfig.qrCodeSize / max(outputImage.extent.width, 1)))
        let transformed = outputImage.transformed(by: CGAffineTransform(scaleX: CGFloat(scale), y: CGFloat(scale)))
        guard let cgImage = context.createCGImage(transformed, from: transformed.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
