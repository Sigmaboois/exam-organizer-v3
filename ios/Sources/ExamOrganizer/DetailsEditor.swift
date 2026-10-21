import SwiftUI

/// "Fix details": correct what was detected about a paper by hand.
@MainActor
struct DetailsEditor: View {
    var fileName: String
    var initial: PaperMetadata?
    var onSave: (PaperMetadata) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var fields = PaperParser.ManualFields()
    @State private var error: String?
    @State private var loaded = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(fileName)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Section("Subject") {
                    TextField("Subject code, e.g. 0625", text: $fields.subjectCode)
                        .keyboardType(.numberPad)
                    Picker("Qualification", selection: $fields.qualification) {
                        Text("Work it out from the code").tag(String?.none)
                        ForEach(Catalog.qualifications, id: \.self) { Text($0).tag(String?.some($0)) }
                    }
                    if let code = fields.subjectCode.nilIfEmpty {
                        let matches = Subjects.all(for: code)
                        Text(matches.isEmpty ? "Not in Cambridge's current subject list"
                             : matches.map { "\($0.qualification): \($0.name)" }.joined(separator: "\n"))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                Section("Exam series") {
                    TextField("Year, e.g. 2024", text: $fields.year)
                        .keyboardType(.numberPad)
                    Picker("Session", selection: $fields.session) {
                        Text("Choose").tag(String?.none)
                        ForEach(Catalog.sessions, id: \.self) { Text(Catalog.sessionLabel($0)).tag(String?.some($0)) }
                    }
                }
                Section {
                    Picker("File type", selection: $fields.paperType) {
                        Text("Choose").tag(String?.none)
                        ForEach(Catalog.paperTypeOrder, id: \.self) { key in
                            Text(Catalog.paperTypes[key] ?? key).tag(String?.some(key))
                        }
                    }
                    TextField("Paper, e.g. 4", text: $fields.paper)
                        .keyboardType(.numberPad)
                    TextField("Variant, e.g. 2", text: $fields.variant)
                        .keyboardType(.numberPad)
                } header: {
                    Text("Paper")
                } footer: {
                    Text("Grade thresholds and examiner reports cover the whole series, so they don't need a paper or variant.")
                }
                if let error {
                    Section {
                        Label(error, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Fix details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                }
            }
            .onAppear {
                if !loaded {
                    fields = PaperParser.ManualFields(initial)
                    loaded = true
                }
            }
        }
    }

    private func save() {
        let result = PaperParser.completeManual(fields)
        if let message = result.error {
            error = message
            return
        }
        onSave(result.metadata)
        dismiss()
    }
}
