package wagateway

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
)

// Media support (Phase 25 of DOC/context-2.md).
//
// The upload/download flow follows the official whatsmeow documentation (verified
// against the pinned whatsmeow version's upload.go / download.go):
//
//	resp, err := cli.Upload(ctx, data, whatsmeow.MediaImage)
//	imageMsg := &waE2E.ImageMessage{
//		URL: &resp.URL, DirectPath: &resp.DirectPath, MediaKey: resp.MediaKey,
//		FileEncSHA256: resp.FileEncSHA256, FileSHA256: resp.FileSHA256,
//		FileLength: &resp.FileLength, Mimetype: proto.String("image/png"),
//	}
//	cli.SendMessage(ctx, jid, &waE2E.Message{ImageMessage: imageMsg})
//
// Downloads use cli.Download(ctx, msg.GetImageMessage()) which verifies the
// SHA256 and decrypts the payload.
//
// Only gomobile-safe types ([]byte, string, int64) cross the Go <-> Kotlin boundary.

// SendImage uploads and sends a picture. caption may be empty. Returns the message ID.
func (c *Client) SendImage(target string, data []byte, mimetype string, caption string) (string, error) {
	cli := c.cli
	if cli == nil || !cli.IsConnected() {
		return "", errors.New("client is not connected")
	}
	if len(data) == 0 {
		return "", errors.New("image data cannot be empty")
	}
	if strings.TrimSpace(mimetype) == "" {
		mimetype = "image/jpeg"
	}

	recipient, err := resolveRecipient(target)
	if err != nil {
		return "", err
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	resp, err := cli.Upload(ctx, data, whatsmeow.MediaImage)
	if err != nil {
		return "", fmt.Errorf("failed to upload image: %w", err)
	}

	msg := &waE2E.Message{
		ImageMessage: &waE2E.ImageMessage{
			Caption:       proto.String(caption),
			Mimetype:      proto.String(mimetype),
			URL:           &resp.URL,
			DirectPath:    &resp.DirectPath,
			MediaKey:      resp.MediaKey,
			FileEncSHA256: resp.FileEncSHA256,
			FileSHA256:    resp.FileSHA256,
			FileLength:    &resp.FileLength,
		},
	}

	sendResp, err := cli.SendMessage(ctx, recipient, msg)
	if err != nil {
		return "", fmt.Errorf("failed to send image: %w", err)
	}
	return string(sendResp.ID), nil
}

// SendDocument uploads and sends a file as a WhatsApp document. filename is shown
// in the document bubble. Returns the message ID.
func (c *Client) SendDocument(target string, data []byte, mimetype string, filename string) (string, error) {
	cli := c.cli
	if cli == nil || !cli.IsConnected() {
		return "", errors.New("client is not connected")
	}
	if len(data) == 0 {
		return "", errors.New("document data cannot be empty")
	}
	if strings.TrimSpace(mimetype) == "" {
		mimetype = "application/octet-stream"
	}
	if strings.TrimSpace(filename) == "" {
		filename = "document"
	}

	recipient, err := resolveRecipient(target)
	if err != nil {
		return "", err
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	resp, err := cli.Upload(ctx, data, whatsmeow.MediaDocument)
	if err != nil {
		return "", fmt.Errorf("failed to upload document: %w", err)
	}

	msg := &waE2E.Message{
		DocumentMessage: &waE2E.DocumentMessage{
			URL:           &resp.URL,
			DirectPath:    &resp.DirectPath,
			MediaKey:      resp.MediaKey,
			FileEncSHA256: resp.FileEncSHA256,
			FileSHA256:    resp.FileSHA256,
			FileLength:    &resp.FileLength,
			Mimetype:      proto.String(mimetype),
			FileName:      proto.String(filename),
		},
	}

	sendResp, err := cli.SendMessage(ctx, recipient, msg)
	if err != nil {
		return "", fmt.Errorf("failed to send document: %w", err)
	}
	return string(sendResp.ID), nil
}

// SendAudio uploads and sends an audio clip. When voiceNote is true it is sent as a
// push-to-talk (voice note) bubble instead of a plain audio attachment.
func (c *Client) SendAudio(target string, data []byte, mimetype string, voiceNote bool) (string, error) {
	cli := c.cli
	if cli == nil || !cli.IsConnected() {
		return "", errors.New("client is not connected")
	}
	if len(data) == 0 {
		return "", errors.New("audio data cannot be empty")
	}
	if strings.TrimSpace(mimetype) == "" {
		mimetype = "audio/ogg"
	}

	recipient, err := resolveRecipient(target)
	if err != nil {
		return "", err
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	resp, err := cli.Upload(ctx, data, whatsmeow.MediaAudio)
	if err != nil {
		return "", fmt.Errorf("failed to upload audio: %w", err)
	}

	msg := &waE2E.Message{
		AudioMessage: &waE2E.AudioMessage{
			URL:           &resp.URL,
			DirectPath:    &resp.DirectPath,
			MediaKey:      resp.MediaKey,
			FileEncSHA256: resp.FileEncSHA256,
			FileSHA256:    resp.FileSHA256,
			FileLength:    &resp.FileLength,
			Mimetype:      proto.String(mimetype),
			PTT:           proto.Bool(voiceNote),
		},
	}

	sendResp, err := cli.SendMessage(ctx, recipient, msg)
	if err != nil {
		return "", fmt.Errorf("failed to send audio: %w", err)
	}
	return string(sendResp.ID), nil
}

// SendVideo uploads and sends a video clip. caption may be empty.
func (c *Client) SendVideo(target string, data []byte, mimetype string, caption string) (string, error) {
	cli := c.cli
	if cli == nil || !cli.IsConnected() {
		return "", errors.New("client is not connected")
	}
	if len(data) == 0 {
		return "", errors.New("video data cannot be empty")
	}
	if strings.TrimSpace(mimetype) == "" {
		mimetype = "video/mp4"
	}

	recipient, err := resolveRecipient(target)
	if err != nil {
		return "", err
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()

	resp, err := cli.Upload(ctx, data, whatsmeow.MediaVideo)
	if err != nil {
		return "", fmt.Errorf("failed to upload video: %w", err)
	}

	msg := &waE2E.Message{
		VideoMessage: &waE2E.VideoMessage{
			Caption:       proto.String(caption),
			Mimetype:      proto.String(mimetype),
			URL:           &resp.URL,
			DirectPath:    &resp.DirectPath,
			MediaKey:      resp.MediaKey,
			FileEncSHA256: resp.FileEncSHA256,
			FileSHA256:    resp.FileSHA256,
			FileLength:    &resp.FileLength,
		},
	}

	sendResp, err := cli.SendMessage(ctx, recipient, msg)
	if err != nil {
		return "", fmt.Errorf("failed to send video: %w", err)
	}
	return string(sendResp.ID), nil
}

// DownloadMedia downloads and decrypts the attachment of a received media message.
// mediaMessageProto is the marshalled waE2E.ImageMessage / AudioMessage /
// VideoMessage / DocumentMessage (the INNER message, exactly what handleEvent
// marshals) the Kotlin side received through OnMedia.
func (c *Client) DownloadMedia(mediaMessageProto []byte) ([]byte, error) {
	cli := c.cli
	if cli == nil {
		return nil, errors.New("client not initialized")
	}
	if len(mediaMessageProto) == 0 {
		return nil, errors.New("media payload cannot be empty")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()

	// The payload is the marshalled INNER message (ImageMessage, AudioMessage, ...),
	// NOT an outer waE2E.Message: unmarshalling it into waE2E.Message can never
	// populate Message.ImageMessage (imageMessage is field 3 on Message, while the
	// inner payload reuses ImageMessage's own field numbering). That mismatch is what
	// made every received photo/file fail with "no downloadable media found in
	// payload". So unmarshal into each concrete inner type and accept the first one
	// that actually carries download info (URL or MediaKey).
	var img waE2E.ImageMessage
	if err := proto.Unmarshal(mediaMessageProto, &img); err == nil && (img.GetURL() != "" || len(img.GetMediaKey()) > 0) {
		return cli.Download(ctx, &img)
	}
	var aud waE2E.AudioMessage
	if err := proto.Unmarshal(mediaMessageProto, &aud); err == nil && (aud.GetURL() != "" || len(aud.GetMediaKey()) > 0) {
		return cli.Download(ctx, &aud)
	}
	var vid waE2E.VideoMessage
	if err := proto.Unmarshal(mediaMessageProto, &vid); err == nil && (vid.GetURL() != "" || len(vid.GetMediaKey()) > 0) {
		return cli.Download(ctx, &vid)
	}
	var doc waE2E.DocumentMessage
	if err := proto.Unmarshal(mediaMessageProto, &doc); err == nil && (doc.GetURL() != "" || len(doc.GetMediaKey()) > 0) {
		return cli.Download(ctx, &doc)
	}
	return nil, errors.New("no downloadable media found in payload")
}
