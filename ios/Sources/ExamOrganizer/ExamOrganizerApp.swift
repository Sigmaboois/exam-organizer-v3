import SwiftUI

@main
@MainActor
struct ExamOrganizerApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environmentObject(model.organise)
                .environmentObject(model.check)
                .environmentObject(model.scoreBook)
                .onOpenURL { url in
                    model.openIncoming(url)
                }
        }
    }
}
