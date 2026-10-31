import Foundation
import UIKit

/// Select a share source without changing the displayed/live recording.
@MainActor final class RecordingPickerController: UITableViewController {
    let sessions: [RecordingSession]
    private let active: UUID?
    private let selected: (RecordingSession) -> Void
    private let date = DateFormatter()
    private var completed = false

    init(sessions: [RecordingSession], active: UUID?, format: String, selected: @escaping (RecordingSession) -> Void) {
        self.sessions = sessions
        self.active = active
        self.selected = selected
        super.init(style: .insetGrouped)
        title = "Share \(format.uppercased()) recording"
        date.dateStyle = .medium
        date.timeStyle = .medium
        navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem: .cancel, target: self, action: #selector(cancel))
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
    @objc private func cancel() { completed = true; dismiss(animated: true) }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { sessions.count }
    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let session = sessions[indexPath.row]
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        cell.textLabel?.text = date.string(from: session.startedAt)
        cell.detailTextLabel?.text = (session.id == active ? "Recording now · " : "Saved · ") + String(session.id.uuidString.prefix(8))
        cell.accessoryType = .disclosureIndicator
        return cell
    }
    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        guard !completed, sessions.indices.contains(indexPath.row) else { return }
        completed = true
        let session = sessions[indexPath.row]
        if presentingViewController == nil && navigationController?.presentingViewController == nil {
            selected(session) // Also permits the simulator's handler-level check.
        } else { dismiss(animated: true) { self.selected(session) } }
    }

    static func verifySelection(store: RecordingStore, existing: RecordingSession) throws {
        // Add a newer fixture session without modifying the saved track being rendered.
        let newer = try store.begin(at: existing.startedAt.addingTimeInterval(1000))
        try store.append(RecordedFix(latitude: 40.7, longitude: -74.8, time: 9_000_123))
        let sessions = try store.sessions()
        guard sessions.first?.id == newer.id, let olderIndex = sessions.firstIndex(where: { $0.id == existing.id }) else {
            throw RecordingStoreError.corruptSession
        }
        var selection: RecordingSession?
        let picker = RecordingPickerController(sessions: sessions, active: newer.id, format: "gpx") { selection = $0 }
        picker.loadViewIfNeeded()
        let row = IndexPath(row: olderIndex, section: 0)
        let cell = picker.tableView(picker.tableView, cellForRowAt: row)
        guard cell.textLabel?.text?.isEmpty == false else { throw RecordingStoreError.corruptSession }
        picker.tableView(picker.tableView, didSelectRowAt: row)
        // Duplicate selections must not present multiple share sheets.
        picker.tableView(picker.tableView, didSelectRowAt: IndexPath(row: 0, section: 0))
        guard selection?.id == existing.id, try store.activeSession()?.id == newer.id,
              try store.points(in: existing).count == 4 else { throw RecordingStoreError.corruptSession }
        _ = try store.stop()
        // Remove only this temporary fixture; read-phase must reopen the original saved session.
        for suffix in ["csv", "json", "pins"] {
            try? FileManager.default.removeItem(at: store.directory.appendingPathComponent(newer.id.uuidString.lowercased() + "." + suffix))
        }
    }
}
