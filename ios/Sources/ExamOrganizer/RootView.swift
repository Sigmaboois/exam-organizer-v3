import SwiftUI

/// iPad layout: a sidebar with the three screens, the chosen screen beside it.
/// On iPhone the same views collapse into one navigation stack.
@MainActor
struct RootView: View {
    @EnvironmentObject private var app: AppModel
    @State private var showSettings = false
    @State private var showHelp = false

    var body: some View {
        NavigationSplitView {
            List(selection: $app.screen) {
                Section {
                    ForEach(AppModel.Screen.allCases) { screen in
                        NavigationLink(value: screen) {
                            Label(screen.title, systemImage: screen.systemImage)
                        }
                    }
                }
                Section {
                    Button {
                        showHelp = true
                    } label: {
                        Label("How it works", systemImage: "questionmark.circle")
                    }
                    Button {
                        showSettings = true
                    } label: {
                        Label("Settings", systemImage: "gearshape")
                    }
                }
            }
            .navigationTitle("Exam Organizer")
        } detail: {
            NavigationStack {
                switch app.screen ?? .organise {
                case .organise: OrganiseView()
                case .check: CheckView()
                case .classes: ClassesView()
                }
            }
        }
        .overlay(alignment: .bottom) {
            if let toast = app.toast {
                ToastView(toast: toast)
                    .padding(.bottom, 28)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .onTapGesture { app.toast = nil }
                    .task(id: toast.id) {
                        try? await Task.sleep(nanoseconds: 4_000_000_000)
                        if app.toast?.id == toast.id {
                            withAnimation { app.toast = nil }
                        }
                    }
            }
        }
        .animation(.spring(duration: 0.35), value: app.toast)
        .sheet(isPresented: $showSettings) {
            SettingsView().environmentObject(app)
        }
        .sheet(isPresented: $showHelp) {
            HelpView()
        }
    }
}

@MainActor
struct SettingsView: View {
    @EnvironmentObject private var app: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var key = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SecureField("Your own Gemini key (optional)", text: $key)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Link(destination: URL(string: "https://aistudio.google.com/apikey")!) {
                        Label("Get a free key from Google AI Studio", systemImage: "key")
                    }
                } header: {
                    Text("AI checking")
                } footer: {
                    Text(Secrets.geminiAPIKey.isEmpty
                         ? "This copy of the app has no key built in, so add your own free key to check papers."
                         : "Checking already works with the key built into the app. Add your own free key if "
                            + "the shared daily limit keeps running out.")
                }
                Section {
                    Text("Checking uses Google's free Gemini AI. On the free tier Google may use what is sent "
                         + "(the student's pages and the mark scheme) to improve its services, so don't add "
                         + "anything private to the papers.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } header: {
                    Text("Privacy")
                }
                Section("About") {
                    LabeledContent("Subjects known", value: "\(Subjects.table.values.reduce(0) { $0 + $1.count })")
                    LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0")
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        app.geminiKey = key.trimmed
                        dismiss()
                    }
                }
            }
            .onAppear { key = app.geminiKey }
        }
    }
}

@MainActor
struct HelpView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Organise") {
                    Text("Drag Cambridge papers onto the drop area (whole folders are fine), or pick them with "
                         + "Choose files. I read each one and work out the subject, series, paper and variant.")
                    Text("Check what I found, fix anything I couldn't work out, then tap Sort. Exams are copied into "
                         + "tidy folders: Subject › Qualification › Code › Year › Session › Paper › Variant.")
                    Text("Find them in the Files app under On My iPad › Exam Organizer, or choose an iCloud Drive "
                         + "folder to sort into.")
                }
                Section("Check papers") {
                    Text("Add students' solved question papers (export them from Notability or GoodNotes as PDFs, "
                         + "or share them straight to Exam Organizer).")
                    Text("Choose whose paper it is and tap Check. I download the mark scheme and grade thresholds, "
                         + "an AI examiner marks every question, and you get a marked PDF copy.")
                    Text("Open Results to read the feedback and change any mark. The grade and marked copy update.")
                }
                Section("Classes") {
                    Text("Add classes and students, or paste a class list. Checked papers are saved to each "
                         + "student, and each class shows its average, its grades and the topics to revise.")
                    Text("Export a class, or everything, as a spreadsheet for Excel or Numbers.")
                }
                Section {
                    Text("Works with Cambridge (CAIE) IGCSE, O Level and AS & A Level. Inserts, examiner reports "
                         + "and grade threshold files are sorted too. Only question papers can be checked.")
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("How it works")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
