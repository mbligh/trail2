import UIKit
import AVFoundation

/// The initial message captures one fix; live location updates never rewrite edits.
@MainActor final class LocationReportController: UIViewController, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    let editor = UITextView()
    private let message: String
    private let accuracy: Double?
    private let selected: (String, UIImage?) -> Void
    private var photo: UIImage?
    private let preview = UIImageView()
    private let cameraButton = UIButton(type: .system)
    private let removeButton = UIButton(type: .system)
    private var capturing = false
    private var completed = false

    init(message: String, accuracy: Double?, selected: @escaping (String, UIImage?) -> Void) {
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
        cameraButton.addTarget(self, action: #selector(takePhoto), for: .touchUpInside)
        removeButton.setTitle("Remove photo", for: .normal)
        removeButton.addTarget(self, action: #selector(removePhoto), for: .touchUpInside)
        let buttons = UIStackView(arrangedSubviews: [cameraButton, removeButton])
        buttons.distribution = .fillEqually
        preview.contentMode = .scaleAspectFit
        preview.isAccessibilityElement = true
        preview.accessibilityLabel = "Attached report photo"
        let height = preview.heightAnchor.constraint(equalToConstant: 110)
        height.priority = .defaultHigh
        height.isActive = true
        updatePhoto()
        let stack = UIStackView(arrangedSubviews: [label, buttons, preview, editor])
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
        guard !completed, !capturing else { return }
        completed = true
        photo = nil
        updatePhoto()
        dismiss(animated: true)
    }
    @objc private func share() {
        guard !completed, !capturing else { return }
        completed = true
        let attachment = photo
        let text = editor.text.trimmingCharacters(in: .whitespacesAndNewlines)
        if navigationController?.presentingViewController == nil && presentingViewController == nil {
            selected(text, attachment) // Handler-level simulator check.
        } else { dismiss(animated: true) { self.selected(text, attachment) } }
    }

    private func updatePhoto() {
        preview.image = photo
        preview.isHidden = photo == nil
        removeButton.isHidden = photo == nil
        cameraButton.setTitle(photo == nil ? "Take photo" : "Retake photo", for: .normal)
    }
    @objc private func removePhoto() {
        guard !completed, !capturing else { return }
        photo = nil
        updatePhoto()
    }
    @objc private func takePhoto() {
        guard !completed, !capturing, presentedViewController == nil else { return }
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            cameraUnavailable("A camera is not available on this device."); return
        }
        editor.resignFirstResponder()
        capturing = true
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: openCamera()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                Task { @MainActor in
                    guard let self, !self.completed else { return }
                    if granted { self.openCamera() }
                    else { self.cameraDenied() }
                }
            }
        default: cameraDenied()
        }
    }
    private func cameraDenied() {
        capturing = false
        cameraUnavailable("Allow camera access in Settings to attach a photo. You can still share the report text.")
    }
    private func cameraUnavailable(_ message: String) {
        let alert = UIAlertController(title: "Camera unavailable", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert, animated: true)
    }
    private func openCamera() {
        guard !completed, viewIfLoaded?.window != nil, presentedViewController == nil else {
            capturing = false; return
        }
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            capturing = false
            cameraUnavailable("A camera is not available on this device."); return
        }
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.cameraCaptureMode = .photo
        picker.delegate = self
        picker.modalPresentationStyle = .fullScreen
        present(picker, animated: true)
    }
    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        capturing = false
        picker.dismiss(animated: true)
        // Keep any previous attachment and all report edits when a retake is cancelled.
    }
    func imagePickerController(_ picker: UIImagePickerController,
                               didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        guard capturing, !completed else { return }
        capturing = false
        let image = (info[.originalImage] as? UIImage).flatMap(Self.reportImage)
        if let image { photo = image; updatePhoto() }
        picker.dismiss(animated: true) { [weak self] in
            if image == nil { self?.cameraUnavailable("The photo could not be read. Please try again.") }
        }
    }
    private static func reportImage(_ original: UIImage) -> UIImage? {
        guard original.size.width > 0, original.size.height > 0 else { return nil }
        let scale = min(1, 2048 / max(original.size.width, original.size.height))
        let size = CGSize(width: original.size.width * scale, height: original.size.height * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        // Normalize orientation, bound the attachment and omit camera metadata.
        let image = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            original.draw(in: CGRect(origin: .zero, size: size))
        }
        return image.jpegData(compressionQuality: 0.85).flatMap { UIImage(data: $0) }
    }

    static func verifyEditing(message: String) throws {
        let expected = "40.672725, -74.749000\nhttps://www.google.com/maps/@?api=1&map_action=map&center=40.672725%2C-74.749000&zoom=18&basemap=satellite\n\n"
        guard message == expected else { throw RecordingStoreError.corruptSession }
        var sent: [String] = []
        let report = LocationReportController(message: message, accuracy: 3) { text, photo in
            if photo == nil { sent.append(text) }
        }
        report.loadViewIfNeeded()
        guard report.editor.text == expected else { throw RecordingStoreError.corruptSession }
        report.editor.text += "Gate broken 🐴\nPlease repair.\n"
        report.share()
        report.share()
        guard sent == [(expected + "Gate broken 🐴\nPlease repair.")] else { throw RecordingStoreError.corruptSession }
        let cancelled = LocationReportController(message: message, accuracy: nil) { text, photo in
            if photo == nil { sent.append(text) }
        }
        cancelled.loadViewIfNeeded()
        cancelled.cancel()
        cancelled.share()
        guard sent.count == 1 else { throw RecordingStoreError.corruptSession }
    }

    static func verifyPhotos(message: String) throws {
        var sent: [(String, UIImage?)] = []
        let report = LocationReportController(message: message, accuracy: 3) { sent.append(($0, $1)) }
        report.loadViewIfNeeded()
        report.editor.text += "Photo of gate 🐴"
        let edited = report.editor.text!
        func capture(_ color: UIColor, size: CGSize) {
            let image = UIGraphicsImageRenderer(size: size).image { context in
                color.setFill(); context.fill(CGRect(origin: .zero, size: size))
            }
            report.capturing = true
            report.imagePickerController(UIImagePickerController(), didFinishPickingMediaWithInfo: [.originalImage: image])
        }
        capture(.red, size: CGSize(width: 30, height: 20))
        guard report.photo?.size == CGSize(width: 30, height: 20), !report.preview.isHidden,
              report.cameraButton.title(for: .normal) == "Retake photo" else { throw RecordingStoreError.corruptSession }
        let first = report.photo
        report.capturing = true
        report.share() // Ignore Share while a capture/permission request is pending.
        report.imagePickerControllerDidCancel(UIImagePickerController())
        guard report.photo === first, sent.isEmpty, report.editor.text == edited else { throw RecordingStoreError.corruptSession }
        capture(.blue, size: CGSize(width: 25, height: 15))
        guard report.photo?.size == CGSize(width: 25, height: 15), report.photo !== first else { throw RecordingStoreError.corruptSession }
        report.removePhoto()
        guard report.photo == nil, report.preview.isHidden, report.editor.text == edited else { throw RecordingStoreError.corruptSession }
        capture(.green, size: CGSize(width: 3000, height: 1500))
        report.share()
        report.share()
        guard sent.count == 1, sent[0].0 == edited,
              sent[0].1?.size == CGSize(width: 2048, height: 1024) else { throw RecordingStoreError.corruptSession }
        let cancelled = LocationReportController(message: message, accuracy: 3) { sent.append(($0, $1)) }
        cancelled.loadViewIfNeeded()
        cancelled.photo = first
        cancelled.cancel()
        cancelled.share()
        guard sent.count == 1, cancelled.photo == nil else { throw RecordingStoreError.corruptSession }
    }
}
