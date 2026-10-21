import CoreGraphics
import Foundation
import PDFKit
import UIKit

/// Reading and drawing PDFs with Apple's PDFKit. Safe to call from a background task,
/// as long as each PDFDocument is only used by the task that opened it.
enum PDFTools {
    static func open(_ url: URL) throws -> PDFDocument {
        guard let document = PDFDocument(url: url) else {
            throw UserError("This PDF couldn't be opened. It may be damaged or password-protected.")
        }
        if document.isLocked {
            throw UserError("This PDF is password-protected, so I can't read it.")
        }
        return document
    }

    /// All the text, one page after another.
    static func text(of url: URL) throws -> String {
        let document = try open(url)
        return (0..<document.pageCount).compactMap { document.page(at: $0)?.string }.joined(separator: "\n")
    }

    static func pages(of document: PDFDocument) -> [PDFPage] {
        (0..<document.pageCount).compactMap { document.page(at: $0) }
    }

    /// Size of a page as it's shown (rotation applied), in points.
    static func displaySize(of page: PDFPage) -> CGSize {
        let box = page.bounds(for: .mediaBox)
        return page.rotation % 180 == 0 ? box.size : CGSize(width: box.height, height: box.width)
    }

    /// A page as an image at `scale` × 72 dpi, on white.
    static func image(of page: PDFPage, scale: CGFloat) -> UIImage {
        let size = displaySize(of: page)
        let target = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let thumbnail = page.thumbnail(of: target, for: .mediaBox)
        return UIGraphicsImageRenderer(size: target, format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: target))
            thumbnail.draw(in: CGRect(origin: .zero, size: target))
        }
    }

    /// Draw a page into a Core Graphics context whose origin is bottom-left (PDF / bitmap space),
    /// stretched to fill width × height.
    static func draw(_ page: PDFPage, into context: CGContext, width: CGFloat, height: CGFloat) {
        let box = page.bounds(for: .mediaBox)
        guard box.width > 0, box.height > 0 else { return }
        context.saveGState()
        context.scaleBy(x: width / box.width, y: height / box.height)
        context.translateBy(x: -box.minX, y: -box.minY)
        page.draw(with: .mediaBox, to: context)
        context.restoreGState()
    }

    /// Grey pixels (0 = black, 255 = white) of a page, width × height, row by row.
    static func greyPixels(of page: PDFPage, width: Int, height: Int) -> [UInt8] {
        var pixels = [UInt8](repeating: 255, count: width * height)
        pixels.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(
                data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width,
                space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return }
            context.setFillColor(gray: 1, alpha: 1)
            context.fill(CGRect(x: 0, y: 0, width: width, height: height))
            draw(page, into: context, width: CGFloat(width), height: CGFloat(height))
        }
        return pixels
    }

    /// RGB pixels of a page (4 bytes per pixel: R, G, B, unused).
    static func rgbPixels(of page: PDFPage, width: Int, height: Int) -> [UInt8] {
        var pixels = [UInt8](repeating: 255, count: width * height * 4)
        pixels.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(
                data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return }
            context.setFillColor(red: 1, green: 1, blue: 1, alpha: 1)
            context.fill(CGRect(x: 0, y: 0, width: width, height: height))
            draw(page, into: context, width: CGFloat(width), height: CGFloat(height))
        }
        return pixels
    }
}
