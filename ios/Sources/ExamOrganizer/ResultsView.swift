import SwiftUI

/// A checked paper's results: score, grade, every question's marks and feedback.
/// Marks can be changed; Save corrections updates the grade, the marked PDF and the score book.
@MainActor
struct ResultsView: View {
    var paperID: UUID

    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var model: CheckModel
    @EnvironmentObject private var scoreBook: ScoreBook
    @Environment(\.dismiss) private var dismiss

    @State private var awards: [Int] = []
    @State private var saving = false
    @State private var preview: URL?

    private var paper: CheckPaper? { model.find(paperID) }

    var body: some View {
        NavigationStack {
            Group {
                if let paper, let outcome = paper.outcome {
                    content(paper, outcome)
                } else {
                    EmptyState(systemImage: "doc.questionmark", title: "No results", message: "This paper hasn't been checked yet.")
                }
            }
            .navigationTitle(paper?.metadata?.title ?? "Results")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(saving ? "Saving…" : "Save corrections") {
                        Task { @MainActor in await saveCorrections() }
                    }
                    .disabled(!changed || saving)
                }
            }
            .pdfPreview($preview)
            .onAppear(perform: resetAwards)
        }
    }

    private var changed: Bool {
        guard let questions = paper?.outcome?.marking.questions, awards.count == questions.count else { return false }
        return zip(awards, questions).contains { $0 != $1.marksAwarded }
    }

    private var editedTotal: Int { awards.reduce(0, +) }

    private func resetAwards() {
        awards = paper?.outcome?.marking.questions.map(\.marksAwarded) ?? []
    }

    @ViewBuilder
    private func content(_ paper: CheckPaper, _ outcome: CheckOutcome) -> some View {
        let marking = outcome.marking
        List {
            Section {
                HStack(alignment: .center, spacing: 20) {
                    Text("\(changed ? editedTotal : marking.total) / \(marking.maxTotal)")
                        .font(.system(size: 34, weight: .bold, design: .rounded))
                        .foregroundStyle(Color.accentColor)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(marking.percent)%").font(.headline)
                        Text(outcome.grade.map { "Grade \($0) (Cambridge thresholds)" } ?? "Grade not available for this paper")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 4)
                Text(paper.metadata?.describe() ?? "")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if !marking.summary.isEmpty {
                    Text(marking.summary).font(.subheadline)
                }
                if !marking.complete {
                    Label("The question marks add up to \(marking.maxTotal), but the paper is out of "
                          + "\(marking.paperTotalMarks). Some parts may be missing.", systemImage: "exclamationmark.triangle")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }
                if !marking.needsReview.isEmpty {
                    Label("Please double-check: " + marking.needsReview.joined(separator: ", "), systemImage: "eye")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.orange)
                }
                if marking.teacherAdjusted {
                    Label("Marks adjusted by you.", systemImage: "pencil")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                HStack {
                    Button {
                        preview = outcome.markedPDF
                    } label: {
                        Label("Open marked copy", systemImage: "doc.richtext")
                    }
                    ShareLink(item: outcome.markedPDF) {
                        Label("Share", systemImage: "square.and.arrow.up")
                    }
                }
                .buttonStyle(.bordered)
            }

            Section {
                ForEach(Array(marking.questions.enumerated()), id: \.element.id) { index, question in
                    QuestionRow(question: question, award: binding(index))
                }
            } header: {
                Text("Questions")
            } footer: {
                Text("Change a mark with − and +, then tap Save corrections. The grade, the marked PDF and the score book update.")
            }
        }
        .listStyle(.insetGrouped)
    }

    private func binding(_ index: Int) -> Binding<Int> {
        Binding(
            get: { index < awards.count ? awards[index] : 0 },
            set: { value in
                if index < awards.count { awards[index] = value }
            })
    }

    private func saveCorrections() async {
        saving = true
        defer { saving = false }
        do {
            try await model.revise(paperID, awards: awards)
            resetAwards()
            app.showToast("Corrections saved.")
        } catch {
            app.showToast(friendlyMessage(error), error: true)
        }
    }
}

@MainActor
private struct QuestionRow: View {
    var question: MarkedQuestion
    @Binding var award: Int

    private var colour: Color {
        award == question.maxMarks ? .green : (award == 0 ? .red : .orange)
    }

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            VStack(spacing: 6) {
                Text(question.question)
                    .font(.headline)
                Text("\(award) / \(question.maxMarks)")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(colour)
                Stepper("Marks", value: $award, in: 0...max(0, question.maxMarks))
                    .labelsHidden()
                    .disabled(question.maxMarks == 0)
            }
            .frame(width: 110)
            VStack(alignment: .leading, spacing: 5) {
                if !question.topic.isEmpty {
                    Text(question.topic)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.accentColor)
                }
                Text(question.marking).font(.subheadline)
                if !question.feedback.isEmpty {
                    Text(question.feedback)
                        .font(.footnote)
                        .italic()
                        .foregroundStyle(.secondary)
                }
                if !question.studentAnswer.isEmpty {
                    Text("Student wrote: \(question.studentAnswer)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if question.confidence == "low" {
                    Pill(text: "Double-check", systemImage: "eye", tint: .orange)
                }
            }
        }
        .padding(.vertical, 6)
    }
}
