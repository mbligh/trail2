import UIKit

/// The initial message captures one fix; live location updates never rewrite edits.
@MainActor final class LocationReportController: UIViewController {
    let editor = UITextView()
    private let message: String
    private let accuracy: Double?
    private let selected: (String) -> Void
    private var completed = false

    init(message: String, accuracy: Double?, selected: @escaping (String) -> Void) {
        self.message = message
        self.accuracy = accuracy
        self.selected = selected
        super.init(nibName: nil, bundle: nil)
        title = "Share current location"
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem: .cancel, target: self, action: #selector(cancel))
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "Share", style: .plain, target: self, action: #selector(share))
        let label = UILabel()
        label.numberOfLines = 0
        label.font = .preferredFont(forTextStyle: .footnote)
        label.text = accuracy.map { "GPS accuracy ±\(Int($0)) m. Add a description if you like." }
            ?? "Add a description if you like."
        editor.text = message
        editor.font = .preferredFont(forTextStyle: .body)
        editor.autocapitalizationType = .sentences
        editor.accessibilityLabel = "Location report"
        let stack = UIStackView(arrangedSubviews: [label, editor])
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
            stack.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor, constant: -12)
        ])
    }

    @objc private func cancel() {
        guard !completed else { return }
        completed = true
        dismiss(animated: true)
    }
    @objc private func share() {
        guard !completed else { return }
        completed = true
        let text = editor.text.trimmingCharacters(in: .whitespacesAndNewlines)
        if navigationController?.presentingViewController == nil && presentingViewController == nil {
            selected(text) // Handler-level simulator check.
        } else { dismiss(animated: true) { self.selected(text) } }
    }

    static func verifyEditing(message: String) throws {
        let expected = "40.672725, -74.749000\nhttps://www.google.com/maps/@?api=1&map_action=map&center=40.672725%2C-74.749000&zoom=18&basemap=satellite\n\n"
        guard message == expected else { throw RecordingStoreError.corruptSession }
        var sent: [String] = []
        let report = LocationReportController(message: message, accuracy: 3) { sent.append($0) }
        report.loadViewIfNeeded()
        guard report.editor.text == expected else { throw RecordingStoreError.corruptSession }
        report.editor.text += "Gate broken 🐴\nPlease repair.\n"
        report.share()
        report.share()
        guard sent == [(expected + "Gate broken 🐴\nPlease repair.")] else { throw RecordingStoreError.corruptSession }
        let cancelled = LocationReportController(message: message, accuracy: nil) { sent.append($0) }
        cancelled.loadViewIfNeeded()
        cancelled.cancel()
        cancelled.share()
        guard sent.count == 1 else { throw RecordingStoreError.corruptSession }
    }
}
