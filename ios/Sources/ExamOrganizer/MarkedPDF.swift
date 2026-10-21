import Foundation
import PDFKit
import UIKit

/// A marked copy of the student's PDF: a cover page with the score, grade and every question's
/// marks and feedback, then the student's own pages with a red marks box on each page that has answers.
enum MarkedPDF {
    static let red = UIColor(red: 0.80, green: 0.10, blue: 0.10, alpha: 1)
    static let dark = UIColor(red: 0.15, green: 0.15, blue: 0.20, alpha: 1)
    static let grey = UIColor(red: 0.40, green: 0.40, blue: 0.45, alpha: 1)
    static let green = UIColor(red: 0.10, green: 0.50, blue: 0.20, alpha: 1)
    static let amber = UIColor(red: 0.75, green: 0.45, blue: 0.00, alpha: 1)

    static func font(_ size: CGFloat, bold: Bool = false) -> UIFont {
        bold ? UIFont.boldSystemFont(ofSize: size) : UIFont.systemFont(ofSize: size)
    }

    static func attributes(_ size: CGFloat, bold: Bool = false, colour: UIColor = dark) -> [NSAttributedString.Key: Any] {
        [.font: font(size, bold: bold), .foregroundColor: colour]
    }

    /// Height a block of text needs at this width.
    static func height(of text: String, width: CGFloat, _ attributes: [NSAttributedString.Key: Any]) -> CGFloat {
        guard !text.isEmpty else { return 0 }
        let rect = (text as NSString).boundingRect(
            with: CGSize(width: width, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: attributes, context: nil)
        return ceil(rect.height)
    }

    @discardableResult
    static func draw(_ text: String, at point: CGPoint, width: CGFloat,
                     _ attributes: [NSAttributedString.Key: Any]) -> CGFloat {
        let needed = height(of: text, width: width, attributes)
        (text as NSString).draw(with: CGRect(x: point.x, y: point.y, width: width, height: needed),
                                options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: attributes, context: nil)
        return needed
    }

    /// Write the marked copy to `output`. Slow-ish: call it from a background task.
    static func make(student: URL, marking: Marking, output: URL, title: String, studentName: String?,
                     grade: String?) throws {
        let document = try PDFTools.open(student)
        let pages = PDFTools.pages(of: document)
        guard let first = pages.first else { throw UserError("This PDF has no pages.") }
        let coverSize = PDFTools.displaySize(of: first)
        let coverBounds = CGRect(origin: .zero, size: coverSize)

        var byPage: [Int: [MarkedQuestion]] = [:]
        for question in marking.questions {
            byPage[question.page, default: []].append(question)
        }

        try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
        let renderer = UIGraphicsPDFRenderer(bounds: coverBounds)
        try renderer.writePDF(to: output) { context in
            drawCover(context, bounds: coverBounds, marking: marking, title: title, studentName: studentName, grade: grade)
            for (index, page) in pages.enumerated() {
                let size = PDFTools.displaySize(of: page)
                let bounds = CGRect(origin: .zero, size: size)
                context.beginPage(withBounds: bounds, pageInfo: [:])
                let cg = context.cgContext
                // UIKit draws top-down, PDF pages bottom-up: flip, then draw the original page as vectors
                cg.saveGState()
                cg.translateBy(x: 0, y: size.height)
                cg.scaleBy(x: 1, y: -1)
                if page.rotation % 360 != 0 {
                    // Let PDFKit apply the page's own rotation
                    cg.concatenate(page.transform(for: .mediaBox))
                    page.draw(with: .mediaBox, to: cg)
                } else {
                    PDFTools.draw(page, into: cg, width: size.width, height: size.height)
                }
                cg.restoreGState()
                if let questions = byPage[index + 1], !questions.isEmpty {
                    drawMarksBox(questions, pageSize: size)
                }
            }
        }
    }

    private static func drawCover(_ context: UIGraphicsPDFRendererContext, bounds: CGRect, marking: Marking,
                                  title: String, studentName: String?, grade: String?) {
        let margin: CGFloat = 50
        let width = bounds.width - 2 * margin
        var y = margin
        context.beginPage()

        func newPage() {
            context.beginPage()
            y = margin
        }

        y += draw("Marked by Exam Organizer", at: CGPoint(x: margin, y: y), width: width, attributes(20, bold: true)) + 6
        y += draw(title, at: CGPoint(x: margin, y: y), width: width, attributes(11, colour: grey)) + 2
        if let studentName {
            y += draw("Student: \(studentName)", at: CGPoint(x: margin, y: y), width: width, attributes(11, colour: grey)) + 2
        }

        // Score box
        y += 10
        let box = CGRect(x: margin, y: y, width: width, height: 56)
        let boxPath = UIBezierPath(roundedRect: box, cornerRadius: 6)
        UIColor(red: 0.95, green: 0.96, blue: 1.0, alpha: 1).setFill()
        boxPath.fill()
        UIColor(red: 0.80, green: 0.84, blue: 0.95, alpha: 1).setStroke()
        boxPath.stroke()
        draw("\(marking.total) / \(marking.maxTotal)", at: CGPoint(x: margin + 16, y: y + 12), width: 170,
             attributes(26, bold: true, colour: red))
        draw("\(marking.percent)%", at: CGPoint(x: margin + 190, y: y + 10), width: width - 200, attributes(14, bold: true))
        if let grade {
            draw("Grade \(grade) (Cambridge thresholds)", at: CGPoint(x: margin + 190, y: y + 30), width: width - 200,
                 attributes(10, colour: grey))
        }
        y = box.maxY + 16

        if !marking.summary.isEmpty {
            y += draw(marking.summary, at: CGPoint(x: margin, y: y), width: width, attributes(10)) + 6
        }
        if !marking.needsReview.isEmpty {
            y += draw("Please double-check: " + marking.needsReview.joined(separator: ", "),
                      at: CGPoint(x: margin, y: y), width: width, attributes(10, bold: true, colour: amber)) + 4
        }
        if marking.teacherAdjusted {
            y += draw("Marks adjusted by the teacher.", at: CGPoint(x: margin, y: y), width: width,
                      attributes(10, bold: true)) + 4
        }
        y += 10

        let questionX = margin
        let marksX = margin + 70
        let textX = margin + 125
        let textWidth = bounds.width - margin - textX

        func tableHeader() {
            draw("Question", at: CGPoint(x: questionX, y: y), width: 70, attributes(9, bold: true, colour: grey))
            draw("Marks", at: CGPoint(x: marksX, y: y), width: 55, attributes(9, bold: true, colour: grey))
            draw("Marking and feedback", at: CGPoint(x: textX, y: y), width: textWidth, attributes(9, bold: true, colour: grey))
            y += 16
        }

        tableHeader()
        for question in marking.questions {
            let markingStyle = attributes(9)
            let feedbackStyle = attributes(9, colour: grey)
            let needed = max(14, height(of: question.marking, width: textWidth, markingStyle)
                             + height(of: question.feedback, width: textWidth, feedbackStyle) + 2) + 10
            if y + needed > bounds.height - margin {
                newPage()
                tableHeader()
            }
            let colour = question.marksAwarded == question.maxMarks ? green : (question.marksAwarded == 0 ? red : amber)
            draw(question.question, at: CGPoint(x: questionX, y: y), width: 68, attributes(10, bold: true))
            draw("\(question.marksAwarded) / \(question.maxMarks)", at: CGPoint(x: marksX, y: y), width: 55,
                 attributes(10, bold: true, colour: colour))
            let markingHeight = draw(question.marking, at: CGPoint(x: textX, y: y), width: textWidth, markingStyle)
            draw(question.feedback, at: CGPoint(x: textX, y: y + markingHeight + 2), width: textWidth, feedbackStyle)
            y += needed
        }
    }

    /// A red box in the top-right corner listing the marks for the questions on this page.
    private static func drawMarksBox(_ questions: [MarkedQuestion], pageSize: CGSize) {
        let boxWidth: CGFloat = 130
        let boxHeight: CGFloat = 20 + 14 * CGFloat(questions.count)
        let box = CGRect(x: pageSize.width - boxWidth - 14, y: 14, width: boxWidth, height: boxHeight)
        let path = UIBezierPath(roundedRect: box, cornerRadius: 4)
        UIColor(red: 1, green: 0.97, blue: 0.97, alpha: 0.95).setFill()
        path.fill()
        red.setStroke()
        path.lineWidth = 1
        path.stroke()
        draw("Marks", at: CGPoint(x: box.minX + 8, y: box.minY + 4), width: boxWidth - 16, attributes(8, bold: true, colour: red))
        for (index, question) in questions.enumerated() {
            draw("\(question.question):  \(question.marksAwarded)/\(question.maxMarks)",
                 at: CGPoint(x: box.minX + 8, y: box.minY + 17 + 14 * CGFloat(index)), width: boxWidth - 16,
                 attributes(9, bold: true, colour: red))
        }
    }
}
