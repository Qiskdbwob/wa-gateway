package wagateway

// WaEventListener defines the callback interface called from Go into Kotlin/Android.
// Only gomobile-safe types (string, bool, int64) cross the Go <-> Kotlin boundary.
type WaEventListener interface {
	// OnQRCode is called every time WhatsApp issues a new pairing QR code.
	OnQRCode(code string)

	// OnPairingCode is called when an 8 digit phone pairing code is available.
	OnPairingCode(code string)

	// OnConnectionStatus is called with a human readable connection state, e.g.
	// "Connecting", "Waiting for QR", "Connected", "Disconnected", "Logged out".
	OnConnectionStatus(status string)

	// OnMessage is called for every incoming text message that is NOT sent by
	// this device.
	//
	//	sender    - the author of the message (group participant inside a group chat)
	//	chat      - the conversation JID; use this when replying
	//	isGroup   - true when the message came from a group conversation
	//	text      - plain text content
	//	messageID - WhatsApp message ID (Info.ID), needed to send a read receipt
	//	            (MarkRead) or to edit the message later
	//	timestamp - unix timestamp in seconds
	OnMessage(sender string, chat string, isGroup bool, text string, messageID string, timestamp int64)

	// OnMedia is called for every incoming media message (image, audio, video,
	// document) that is NOT sent by this device.
	//
	//	mediaType - "image", "audio", "video" or "document"
	//	mimetype  - e.g. "image/jpeg", "audio/ogg; codecs=opus"
	//	caption   - user caption when present (image/video/document), otherwise ""
	//	filename  - document file name when present, otherwise ""
	//	payload   - marshalled protobuf of the specific media message (waE2E.ImageMessage
	//	            / AudioMessage / VideoMessage / DocumentMessage). Pass it back to
	//	            Client.DownloadMedia to download and decrypt the attachment.
	OnMedia(sender string, chat string, isGroup bool, mediaType string, mimetype string, caption string, filename string, messageID string, timestamp int64, payload []byte)
}
